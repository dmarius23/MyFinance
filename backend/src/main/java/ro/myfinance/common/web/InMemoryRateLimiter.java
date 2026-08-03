package ro.myfinance.common.web;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-instance token-bucket limiter — no external store. With several app instances the effective ceiling
 * is the configured limit times the instance count; for one shared limit use {@link RedisRateLimiter}.
 */
class InMemoryRateLimiter extends AbstractRateLimiter {

    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    InMemoryRateLimiter(boolean enabled, int uploadsPerMinute, int emailsPerMinute) {
        super(enabled, uploadsPerMinute, emailsPerMinute);
    }

    @Override
    protected boolean tryConsume(String key, int perMinute) {
        return buckets.computeIfAbsent(key, k -> new TokenBucket(perMinute)).tryConsume();
    }

    /** Continuously-refilled token bucket: {@code perMinute} capacity, refilled at {@code perMinute}/minute. */
    private static final class TokenBucket {
        private final double capacity;
        private final double refillPerMilli;
        private double tokens;
        private long lastRefill;

        TokenBucket(int perMinute) {
            this.capacity = perMinute;
            this.refillPerMilli = perMinute / 60_000.0;
            this.tokens = perMinute;
            this.lastRefill = System.currentTimeMillis();
        }

        synchronized boolean tryConsume() {
            long now = System.currentTimeMillis();
            tokens = Math.min(capacity, tokens + (now - lastRefill) * refillPerMilli);
            lastRefill = now;
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
