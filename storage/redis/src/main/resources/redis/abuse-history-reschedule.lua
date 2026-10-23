local t = redis.call('TIME')
local now = tonumber(t[1])*1000 + math.floor(tonumber(t[2])/1000)
local value = redis.call('HMGET', KEYS[1], 'state', 'context', 'dbPersisted', 'ownerToken', 'leaseUntil')
if value[1] ~= 'ACTIVE' or value[2] ~= 'DB_CONTEXT_CHECKED' or value[3] ~= 'false'
    or value[4] ~= ARGV[2] or (tonumber(value[5]) or 0) <= now then return '0' end
-- 복구 원본인 저널 상태를 먼저 바꾸고 파생 큐를 정렬한다. Lua 오류를 롤백으로 간주하지 않는다.
redis.call('HSET', KEYS[1], 'firstFailureAt', ARGV[3], 'failureCount', ARGV[4],
  'nextAttemptAt', ARGV[5], 'deliveryState', ARGV[6])
local target = ARGV[6] == 'BATCH' and KEYS[3] or KEYS[2]
local other = ARGV[6] == 'BATCH' and KEYS[2] or KEYS[3]
redis.call('ZADD', target, ARGV[5], ARGV[1])
redis.call('ZREM', other, ARGV[1])
redis.call('HDEL', KEYS[1], 'ownerToken', 'leaseUntil')
return '1'
