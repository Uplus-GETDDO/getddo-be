-- 저장 소유권만 임대한다. 실제 차단 종료·최초 payload는 변경하지 않는다.
local t = redis.call('TIME')
local now = tonumber(t[1])*1000 + math.floor(tonumber(t[2])/1000)
local prefix = 'getddo:entry-block:'
local queue = ARGV[1] == 'BATCH' and KEYS[2] or KEYS[1]
local cutoff = ARGV[5] ~= '' and math.min(now, tonumber(ARGV[5])) or now
local ids = redis.call('ZRANGEBYSCORE', queue, '-inf', cutoff, 'LIMIT', 0, tonumber(ARGV[4]))
for _, id in ipairs(ids) do
  local journal = prefix .. 'journal:' .. id
  local value = redis.call('HMGET', journal, 'state', 'context', 'dbPersisted', 'payload',
    'deliveryState', 'firstFailureAt', 'failureCount', 'nextAttemptAt', 'leaseUntil')
  if redis.call('EXISTS', journal) == 0 then
    -- 파생 항목만으로 원본이나 차단을 합성하지 않는다. 사용자별 고아 인덱스는 정기 복구가 정리한다.
    redis.call('ZREM', KEYS[1], id)
    redis.call('ZREM', KEYS[2], id)
    redis.call('SREM', KEYS[3], id)
  elseif value[1] == 'PREPARED' or value[3] == 'true' then
    redis.call('ZREM', KEYS[1], id)
    redis.call('ZREM', KEYS[2], id)
  else
    local mode = value[5] or 'NORMAL'
    local due = tonumber(value[8]) or 0
    local leasedUntil = tonumber(value[9]) or 0
    if mode ~= ARGV[1] then
      redis.call('ZREM', queue, id)
    elseif leasedUntil > now or due > cutoff then
      redis.call('ZADD', queue, math.max(due, leasedUntil), id)
    else
      local untilAt = now + tonumber(ARGV[3])
      redis.call('HSET', journal, 'ownerToken', ARGV[2], 'leaseUntil', untilAt)
      redis.call('ZADD', queue, untilAt, id)
      return cjson.encode({id=id, claimedAt=now, state=value[1] or '', context=value[2] or '',
        dbPersisted=value[3] or '', payload=value[4] or '', firstFailureAt=value[6] or cjson.null,
        failureCount=value[7] or '0'})
    end
  end
end
-- 정리한 첫 묶음 뒤에도 대상이 있을 수 있으므로 호출자는 다음 묶음을 계속 읽는다.
return #ids > 0 and cjson.encode({retry=true}) or ''
