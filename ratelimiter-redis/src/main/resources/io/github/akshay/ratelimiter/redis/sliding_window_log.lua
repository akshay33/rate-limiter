-- Sliding window log: allow if fewer than `limit` requests fall within the last `window`.
-- One sorted-set entry per allowed request, scored by its time. Atomic, like all scripts.
--
-- KEYS[1]  sorted set of request timestamps (microseconds)
-- ARGV[1]  limit
-- ARGV[2]  window in microseconds
-- ARGV[3]  permits requested
-- ARGV[4]  unique id for this call (members must be unique)
-- ARGV[5]  key expiry in milliseconds (one window, plus a buffer)
--
-- Returns {allowed (1 or 0), retry_after_micros}

local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local permits = tonumber(ARGV[3])
local call_id = ARGV[4]
local ttl_ms = tonumber(ARGV[5])

local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000000 + tonumber(time[2])
-- %.17g keeps every digit of 16-digit microsecond timestamps.
local now_str = string.format('%.17g', now)

-- An entry leaves the window once a full window has passed since it was added.
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', string.format('%.17g', now - window))
local count = redis.call('ZCARD', KEYS[1])

if count + permits <= limit then
    for i = 1, permits do
        redis.call('ZADD', KEYS[1], now_str, call_id .. ':' .. i)
    end
    redis.call('PEXPIRE', KEYS[1], ttl_ms)
    return {1, 0}
end

-- Enough room frees up when the k-th oldest entry leaves the window.
local k = count + permits - limit
local entry = redis.call('ZRANGE', KEYS[1], k - 1, k - 1, 'WITHSCORES')
return {0, tonumber(entry[2]) + window - now}
