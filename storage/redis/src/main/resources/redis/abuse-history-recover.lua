local t = redis.call('TIME')
local now = tonumber(t[1])*1000 + math.floor(tonumber(t[2])/1000)
if redis.call('EXISTS', KEYS[1]) == 0 then
  redis.call('SREM', KEYS[3], ARGV[1])
  redis.call('ZREM', KEYS[4], ARGV[1])
  redis.call('ZREM', KEYS[5], ARGV[1])
  return 'MISSING'
end
local value = redis.call('HMGET', KEYS[1], 'state', 'context', 'dbPersisted', 'payload',
  'deliveryState', 'nextAttemptAt', 'leaseUntil')
local function cleanup()
  if ARGV[2] ~= '' then redis.call('SREM', KEYS[2], ARGV[1]) end
  redis.call('SREM', KEYS[3], ARGV[1])
  redis.call('ZREM', KEYS[4], ARGV[1])
  redis.call('ZREM', KEYS[5], ARGV[1])
  -- 저널을 마지막에 삭제한다. 앞 명령 실패로 남아도 다음 복구가 원본에서 재개한다.
  redis.call('DEL', KEYS[1])
end
-- 생성 EVAL과 정리는 직렬화된다. 스크립트가 끝난 뒤 남은 PREPARED는 실제 차단이 아니다.
if value[1] == 'PREPARED' and (value[4] or '') == ARGV[3] then cleanup(); return 'PREPARED_REMOVED' end
if value[1] ~= 'ACTIVE' or value[2] ~= 'DB_CONTEXT_CHECKED' or value[4] ~= ARGV[3]
    or ARGV[2] == '' then return 'UNREADABLE' end
if value[3] == 'true' then
  redis.call('ZREM', KEYS[4], ARGV[1])
  redis.call('ZREM', KEYS[5], ARGV[1])
  redis.call('HDEL', KEYS[1], 'ownerToken', 'leaseUntil')
  if tonumber(ARGV[4]) <= now then cleanup(); return 'PERSISTED_REMOVED' end
elseif value[3] == 'false' then
  local due = tonumber(value[6]) or tonumber(ARGV[5])
  local lease = tonumber(value[7]) or 0
  local score = math.max(due, lease > now and lease or 0)
  local queue = value[5] or 'NORMAL'
  if queue ~= 'NORMAL' and queue ~= 'BATCH' then return 'UNREADABLE' end
  local target = queue == 'BATCH' and KEYS[5] or KEYS[4]
  local other = queue == 'BATCH' and KEYS[4] or KEYS[5]
  redis.call('ZADD', target, score, ARGV[1])
  redis.call('ZREM', other, ARGV[1])
else return 'UNREADABLE' end
-- 미저장·저장 완료 ACTIVE 모두 종료 전 원래 차단 조회를 계속 제공한다.
redis.call('SADD', KEYS[2], ARGV[1])
redis.call('SADD', KEYS[3], ARGV[1])
return 'RECOVERED'
