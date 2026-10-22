-- Redis 단일 인스턴스 전용. TIME과 저널을 차단 유효성의 원본으로 사용한다.
-- 여러 API 서버의 시계 차이로 차단 종료 판단이 달라지지 않도록 Redis 시각을 사용한다.
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local prefix = 'getddo:entry-block:'
local function active(index)
  local ids = redis.call('SMEMBERS', index)
  for _, id in ipairs(ids) do
    local values = redis.call('HMGET', prefix .. 'journal:' .. id, 'state', 'context', 'payload')
    if values[1] == 'ACTIVE' and values[2] == 'DB_CONTEXT_CHECKED' and values[3] then
      local payload = cjson.decode(values[3])
      if payload.userId == ARGV[1] and payload.sourceType == 'ENTRY' and payload.blockedUntil > now then
        return cjson.encode({result='BLOCKED', recordingStatus='NOT_APPLICABLE',
          retryAfterSeconds=math.max(1, math.ceil((payload.blockedUntil-now)/1000)), block=payload})
      end
    end
  end
  return nil
end
-- KEYS: 사용자 인덱스, 집계 HASH, 후보 저널, 전역 저널 인덱스, 저장 대기 ZSET.
-- ARGV: 사용자, 이벤트 scope, 검증한 이벤트, 수신 시각, 후보 ID, 구간ms,
--       전체 한도, 이벤트 한도(없으면0), 전체 거절 임계값, 이벤트 거절 임계값, 차단ms, 규칙 버전.
-- 사전 읽기 뒤 다른 요청이 차단을 시작했을 수 있다. 쓰기 직전 재확인해 연장·추가 집계·중복 이력을 막는다.
local blocked = active(KEYS[1])
if blocked then return blocked end
local window = tonumber(ARGV[6])
local userLimit = tonumber(ARGV[7])
local eventLimit = tonumber(ARGV[8])
local userThreshold = tonumber(ARGV[9])
local eventThreshold = tonumber(ARGV[10])
-- 거절 횟수도 일반 요청과 같은 구간에 보관한다. 첫 429부터 별도 구간을 시작하지 않는다.
local function load(field)
  local raw = redis.call('HGET', KEYS[2], field)
  local value = raw and cjson.decode(raw) or nil
  if not value or now >= value.endsAt then
    return {startedAt=now, endsAt=now+window, count=0, rejectedCount=0}
  end
  return value
end
local all = redis.call('HGETALL', KEYS[2])
local user = load('user')
local eventField = ARGV[2] ~= '' and 'event:' .. ARGV[2] or nil
local event = eventField and load(eventField) or nil
user.count = math.min(user.count+1, userLimit+1)
local denied = user.count > userLimit
local retryAt = user.endsAt
-- 기존 일반 제한과 같이 USER에서 거절되면 EVENT 일반 카운터 증가는 생략한다.
if not denied and event and eventLimit > 0 then
  event.count = math.min(event.count+1, eventLimit+1)
  denied = event.count > eventLimit
  retryAt = event.endsAt
end
-- 일반 한도로 거절된 요청만 두 범위의 거절 횟수에 각각 한 번 기록한다. 허용·업무 거절은 포함하지 않는다.
if denied then
  user.rejectedCount = math.min(user.rejectedCount+1, 2147483647)
  if event then event.rejectedCount = math.min(event.rejectedCount+1, 2147483647) end
end
local result = {result=denied and 'RATE_LIMITED' or 'ALLOWED',
  recordingStatus=denied and 'RECORDED' or 'NOT_APPLICABLE',
  retryAfterSeconds=denied and math.max(1, math.ceil((retryAt-now)/1000)) or 0,
  userWindow=user, eventWindow=event or cjson.null}
-- Lua의 명령 오류는 앞선 쓰기를 되돌리지 않는다. 이미 확인한 일반 429는 유지하고 기록 실패를 구분한다.
local function failed()
  if denied then
    result.recordingStatus='DENY_RECORDING_FAILED'
    return cjson.encode(result)
  end
  return redis.error_reply('ENTRY_EVALUATION_FAILED')
end
local function safe(...)
  local value = redis.pcall(...)
  return not (type(value)=='table' and value.err)
end
-- 사용자와 이벤트 거절 상태를 한 HSET으로 교체해 한쪽만 반영되지 않게 한다.
local fields = {'user', cjson.encode(user)}
if eventField then
  table.insert(fields, eventField)
  table.insert(fields, cjson.encode(event))
end
if not safe('HSET', KEYS[2], unpack(fields)) then return failed() end
-- 현재 설정이 바뀌어도 아직 유효한 각 구간의 최초 endsAt은 유지한다.
local expiresAt = math.max(user.endsAt, event and event.endsAt or 0)
for i=1,#all,2 do
  if all[i] ~= 'user' and all[i] ~= eventField then
    local old = cjson.decode(all[i+1])
    if now >= old.endsAt then
      if not safe('HDEL', KEYS[2], all[i]) then return failed() end
    else
      expiresAt = math.max(expiresAt, old.endsAt)
    end
  end
end
if not safe('PEXPIREAT', KEYS[2], expiresAt) then return failed() end
if not denied then return cjson.encode(result) end
local overall = user.rejectedCount >= userThreshold
local perEvent = event and eventLimit > 0 and event.rejectedCount >= eventThreshold
if not overall and not perEvent then return cjson.encode(result) end
-- 잘못된 파생 자료구조는 ACTIVE 전에 발견한다. 기존 저널을 후보로 덮어쓰지 않는다.
local expected = {'set', 'hash', 'none', 'set', 'zset'}
for i=1,#KEYS do
  local kind = redis.call('TYPE', KEYS[i]).ok
  if kind ~= 'none' and kind ~= expected[i] then return failed() end
end
if redis.call('EXISTS', KEYS[3]) ~= 0 then return failed() end
-- 동시 충족이면 USER만 대표 근거로 저장한다. 이후 재시도에서도 최초 시각·근거를 바꾸지 않는다.
local representative = overall and user or event
local payload = {id=ARGV[5], userId=ARGV[1], eventId=ARGV[3] ~= '' and ARGV[3] or cjson.null,
  sourceType='ENTRY', detectionType='REPEATED_RATE_LIMIT_EXCEEDED',
  detectionReason='응모 요청 한도 초과가 반복되어 일시 차단되었습니다.', occurredAt=ARGV[4],
  detectedAt=now, blockedAt=now, blockedUntil=now+tonumber(ARGV[11]),
  evidence={ruleVersion=ARGV[12], triggerScope=overall and 'USER' or 'EVENT',
    windowStartedAt=representative.startedAt, windowEndsAt=representative.endsAt,
    requestLimit=overall and userLimit or eventLimit, rejectedCount=representative.rejectedCount,
    rejectionThreshold=overall and userThreshold or eventThreshold}}
local encoded = cjson.encode(payload)
local function cancel()
  -- pcall 오류는 롤백이 아니다. 미활성 후보만 정리하며 ACTIVE는 취소하지 않는다.
  safe('DEL', KEYS[3])
  safe('SREM', KEYS[1], ARGV[5])
  safe('SREM', KEYS[4], ARGV[5])
  safe('ZREM', KEYS[5], ARGV[5])
  return failed()
end
-- 저널은 차단 원본이자 후속 DB 저장의 원본이다. 인덱스·대기 등록을 끝내기 전에는 활성화하지 않는다.
-- DB 미저장 이력을 만료와 함께 잃지 않도록 저널·인덱스·pending에는 차단 TTL을 설정하지 않는다.
if not safe('HSET', KEYS[3], 'state', 'PREPARED', 'context', 'DB_CONTEXT_CHECKED',
    'dbPersisted', 'false', 'payload', encoded) then return cancel() end
if not safe('SADD', KEYS[1], ARGV[5]) then return cancel() end
if not safe('SADD', KEYS[4], ARGV[5]) then return cancel() end
if not safe('ZADD', KEYS[5], now, ARGV[5]) then return cancel() end
-- 저장 대기 등록 후 단일 명령으로 최초 ACTIVE와 전체 payload를 확정한다.
if not safe('HSET', KEYS[3], 'state', 'ACTIVE', 'payload', encoded) then return cancel() end
return cjson.encode({result='BLOCKED', recordingStatus='RECORDED',
  retryAfterSeconds=math.max(1, math.ceil((payload.blockedUntil-now)/1000)),
  userWindow=user, eventWindow=event or cjson.null, block=payload})
