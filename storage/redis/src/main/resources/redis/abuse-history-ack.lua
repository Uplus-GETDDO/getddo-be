local t = redis.call('TIME')
local now = tonumber(t[1])*1000 + math.floor(tonumber(t[2])/1000)
local value = redis.call('HMGET', KEYS[1], 'state', 'context', 'ownerToken', 'leaseUntil')
if value[1] ~= 'ACTIVE' or value[2] ~= 'DB_CONTEXT_CHECKED' or value[3] ~= ARGV[2]
    or (tonumber(value[4]) or 0) <= now then return '0' end
-- DB commit 후에만 호출한다. 완료 표식이 먼저 남아도 정기 복구가 나머지를 이어 정리할 수 있다.
redis.call('HSET', KEYS[1], 'dbPersisted', 'true')
redis.call('ZREM', KEYS[4], ARGV[1])
redis.call('ZREM', KEYS[5], ARGV[1])
redis.call('HDEL', KEYS[1], 'ownerToken', 'leaseUntil')
-- 종료 전에는 ACTIVE와 사용자 인덱스를 유지한다. 저장 성공이 차단 해제를 뜻하지 않는다.
if tonumber(ARGV[3]) <= now then
  redis.call('SREM', KEYS[2], ARGV[1])
  redis.call('SREM', KEYS[3], ARGV[1])
  redis.call('DEL', KEYS[1])
end
return '1'
