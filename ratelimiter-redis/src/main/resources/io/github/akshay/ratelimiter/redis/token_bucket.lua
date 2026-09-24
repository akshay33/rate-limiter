-- Token bucket: refill, then try to take permits, as one atomic step.
-- Redis runs a script to completion without interleaving other commands,
-- so concurrent callers (from any number of servers) can't race on a bucket.
--
-- KEYS[1]  bucket key, a hash with fields: tokens, ts (last refill, microseconds)
-- ARGV[1]  capacity
-- ARGV[2]  refill rate, in tokens per microsecond
-- ARGV[3]  permits requested
-- ARGV[4]  key expiry in milliseconds (time for an empty bucket to refill fully)
--
-- Returns {allowed (1 or 0), retry_after_micros}

local capacity = tonumber(ARGV[1])
local rate = tonumber(ARGV[2])
local permits = tonumber(ARGV[3])
local ttl_ms = tonumber(ARGV[4])

-- Use Redis's clock, not the caller's, so servers with drifting clocks agree.
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000000 + tonumber(time[2])

local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts = tonumber(state[2])
if tokens == nil or ts == nil then
    -- New (or expired) bucket starts full.
    tokens = capacity
    ts = now
end

local elapsed = now - ts
if elapsed > 0 then
    tokens = math.min(capacity, tokens + elapsed * rate)
    ts = now
end

local allowed = 0
local retry_after = 0
if tokens >= permits then
    tokens = tokens - permits
    allowed = 1
else
    retry_after = math.ceil((permits - tokens) / rate)
end

-- %.17g keeps full double precision (the default number format loses digits).
redis.call('HSET', KEYS[1], 'tokens', string.format('%.17g', tokens), 'ts', string.format('%.17g', ts))
-- Once an idle bucket has refilled completely it equals a new one, so Redis can drop it.
redis.call('PEXPIRE', KEYS[1], ttl_ms)

return {allowed, retry_after}
