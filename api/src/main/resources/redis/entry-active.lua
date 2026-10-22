-- Redis 단일 인스턴스 전용. TIME과 저널을 차단 유효성의 원본으로 사용한다.
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
-- 읽기 전용 경로는 인덱스·저널·TTL을 변경하지 않는다.
return active(KEYS[1]) or ''
