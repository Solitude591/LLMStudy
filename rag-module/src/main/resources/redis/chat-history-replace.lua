local key = KEYS[1]
local incoming_version = tonumber(ARGV[1])
local ttl_seconds = tonumber(ARGV[3])

local current_value = redis.call('LINDEX', key, 0)
if current_value then
    local current_version = tonumber(current_value)
    if current_version and current_version > incoming_version then
        -- 禁止较旧的 MySQL 查询快照覆盖并发写入的新窗口。
        return 0
    end
end

redis.call('DEL', key)
-- 即使消息列表为空也保留版本元素，避免空会话反复穿透 MySQL。
redis.call('RPUSH', key, tostring(incoming_version))
for i = 4, #ARGV do
    redis.call('RPUSH', key, ARGV[i])
end
redis.call('EXPIRE', key, ttl_seconds)
return 1
