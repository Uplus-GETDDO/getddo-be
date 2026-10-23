-- 사용자 인덱스 자체를 원본으로 삼지 않는다. 원본이 없거나 다른 사용자에 연결된 항목만 제거한다.
if redis.call('EXISTS', KEYS[1]) == 0 then return tostring(redis.call('SREM', KEYS[2], ARGV[1])) end
local payload = redis.call('HGET', KEYS[1], 'payload')
if not payload then return '0' end
local ok, value = pcall(cjson.decode, payload)
if ok and type(value) == 'table' and value.userId and value.userId ~= ARGV[2] then
  return tostring(redis.call('SREM', KEYS[2], ARGV[1]))
end
return '0'
