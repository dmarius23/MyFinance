package ro.myfinance.common.web;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Distributed fixed-window limiter backed by Redis: every app instance increments the same per-minute
 * counter, so the configured limit is enforced once across the whole cluster (not per instance). Keyed
 * {@code ratelimit:<category>:<tenant>:<user>:<minute>} with a short TTL for self-cleanup.
 *
 * <p><b>Fail-open:</b> if Redis is unreachable the request is allowed (and a warning logged) — a rate
 * limiter must never be able to take the application down.
 */
class RedisRateLimiter extends AbstractRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    /** Window + slack, so a key created near a minute boundary still expires well after the window ends. */
    private static final Duration KEY_TTL = Duration.ofSeconds(70);

    private final StringRedisTemplate redis;

    RedisRateLimiter(StringRedisTemplate redis, boolean enabled, int uploadsPerMinute, int emailsPerMinute) {
        super(enabled, uploadsPerMinute, emailsPerMinute);
        this.redis = redis;
    }

    @Override
    protected boolean tryConsume(String key, int perMinute) {
        long windowMinute = System.currentTimeMillis() / 60_000L;
        String redisKey = "ratelimit:" + key + ":" + windowMinute;
        try {
            Long count = redis.opsForValue().increment(redisKey);
            if (count != null && count == 1L) {
                redis.expire(redisKey, KEY_TTL); // first hit in this window: start the countdown
            }
            return count == null || count <= perMinute;
        } catch (RuntimeException e) {
            // Redis down / timeout: fail open rather than block writes on an infra blip.
            log.warn("Rate-limit check failed, allowing request (fail-open): {}", e.toString());
            return true;
        }
    }
}
