-- Sliding window counter: counts for the current and previous fixed window; the last `window`
-- is estimated as current + previous * (share of the previous window still inside it).
--
-- KEYS[1]  hash with fields: w (current window index), cur, prev
-- ARGV[1]  limit
-- ARGV[2]  window in microseconds
-- ARGV[3]  permits requested
-- ARGV[4]  key expiry in milliseconds (two windows, plus a buffer)
--
-- Returns {allowed (1 or 0), retry_after_micros}

local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local permits = tonumber(ARGV[3])
local ttl_ms = tonumber(ARGV[4])

local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000000 + tonumber(time[2])
local index = math.floor(now / window)

local state = redis.call('HMGET', KEYS[1], 'w', 'cur', 'prev')
local w = tonumber(state[1])
local cur = tonumber(state[2]) or 0
local prev = tonumber(state[3]) or 0
if w == nil then
    w = index
end

-- Move to the current window; counts older than one window back no longer matter.
if index ~= w then
    if index == w + 1 then
        prev = cur
    else
        prev = 0
    end
    cur = 0
    w = index
end

local elapsed = now - index * window
local estimate = cur + prev * (1 - elapsed / window)

local allowed = 0
local retry_after = 0
if estimate + permits <= limit then
    cur = cur + permits
    allowed = 1
else
    local room = limit - permits
    if cur <= room then
        -- Within this window: prev * (1 - t / window) <= room - cur
        local t = window * (1 - (room - cur) / prev)
        retry_after = math.max(math.ceil(t - elapsed), 1)
    else
        -- Next window, where cur becomes the shrinking "previous" count: cur * (1 - t / window) <= room
        local t_next = window * (1 - room / cur)
        retry_after = math.ceil((window - elapsed) + t_next)
    end
end

redis.call('HSET', KEYS[1], 'w', string.format('%.17g', w), 'cur', cur, 'prev', prev)
redis.call('PEXPIRE', KEYS[1], ttl_ms)
return {allowed, retry_after}
