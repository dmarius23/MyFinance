package ro.myfinance.common.web;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A small in-memory token-bucket rate limiter for the expensive write endpoints (document upload and
 * email dispatch), keyed per caller. Limits are per application instance — a coarse abuse guard that
 * needs no external store; a distributed (Redis) limiter can replace this behind the same methods later.
 * Configure with {@code myfinance.ratelimit.*}; a non-positive limit or {@code enabled=false} disables it.
 */
@Component
public class RateLimiter {

    private final boolean enabled;
    private final int uploadsPerMinute;
    private final int emailsPerMinute;
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(
            @Value("${myfinance.ratelimit.enabled:true}") boolean enabled,
            @Value("${myfinance.ratelimit.uploads-per-minute:60}") int uploadsPerMinute,
            @Value("${myfinance.ratelimit.emails-per-minute:30}") int emailsPerMinute) {
        this.enabled = enabled;
        this.uploadsPerMinute = uploadsPerMinute;
        this.emailsPerMinute = emailsPerMinute;
    }

    /** True if the caller may perform another upload now. */
    public boolean allowUpload(String caller) {
        return allow("upload", caller, uploadsPerMinute);
    }

    /** True if the caller may trigger another email now. */
    public boolean allowEmail(String caller) {
        return allow("email", caller, emailsPerMinute);
    }

    private boolean allow(String category, String caller, int perMinute) {
        if (!enabled || perMinute <= 0) {
            return true;
        }
        return buckets.computeIfAbsent(category + ":" + caller, k -> new TokenBucket(perMinute)).tryConsume();
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
