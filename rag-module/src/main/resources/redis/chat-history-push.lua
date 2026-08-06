local key = KEYS[1]
local next_version = tonumber(ARGV[1])
local message_json = ARGV[2]
local window_size = tonumber(ARGV[3])
local ttl_seconds = tonumber(ARGV[4])

local current_value = redis.call('LINDEX', key, 0)
if not current_value then
    -- 未初始化的 key 不能直接追加，否则会把部分消息误认为完整窗口。
    return 0
end

local current_version = tonumber(current_value)
if not current_version then
    redis.call('DEL', key)
    return -1
end

if current_version == next_version then
    return 0
end

if current_version ~= next_version - 1 then
    -- 版本跳跃意味着存在漏写或乱序，删除窗口并等待 MySQL 重建。
    redis.call('DEL', key)
    return -1
end

redis.call('LSET', key, 0, tostring(next_version))
redis.call('RPUSH', key, message_json)

-- index 0 保存版本，因此列表总长度上限是 window_size + 1。
if redis.call('LLEN', key) > window_size + 1 then
    local recent_messages = redis.call('LRANGE', key, -window_size, -1)
    redis.call('DEL', key)
    redis.call('RPUSH', key, tostring(next_version))
    for i = 1, #recent_messages do
        redis.call('RPUSH', key, recent_messages[i])
    end
end

redis.call('EXPIRE', key, ttl_seconds)
return 1
