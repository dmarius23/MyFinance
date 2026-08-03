package ro.myfinance.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import ro.myfinance.support.AbstractPostgresIT;

/**
 * With {@code myfinance.ratelimit.distributed=true} the Redis-backed limiter must be the wired bean, so the
 * limit is shared across instances. StringRedisTemplate is auto-configured from {@code spring.data.redis};
 * the bean is created lazily, so this boots without a running Redis (the limiter fails open at call time).
 */
@TestPropertySource(properties = "myfinance.ratelimit.distributed=true")
class RateLimiterWiringIT extends AbstractPostgresIT {

    @Autowired RateLimiter rateLimiter;

    @Test
    void distributedFlagSelectsTheRedisBackend() {
        assertThat(rateLimiter).isInstanceOf(RedisRateLimiter.class);
    }
}
