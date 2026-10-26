local t = redis.call('TIME')
return tostring(tonumber(t[1])*1000 + math.floor(tonumber(t[2])/1000))
