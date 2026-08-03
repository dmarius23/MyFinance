package ro.myfinance.common.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Selects the {@link RateLimiter} backend. {@code myfinance.ratelimit.distributed=true} uses the shared
 * Redis limiter (one cluster-wide limit); otherwise the per-instance in-memory limiter is used.
 */
@Configuration
class RateLimiterConfig {

    @Bean
    @ConditionalOnProperty(name = "myfinance.ratelimit.distributed", havingValue = "true")
    RateLimiter redisRateLimiter(
            StringRedisTemplate redis,
            @Value("${myfinance.ratelimit.enabled:true}") boolean enabled,
            @Value("${myfinance.ratelimit.uploads-per-minute:60}") int uploadsPerMinute,
            @Value("${myfinance.ratelimit.emails-per-minute:30}") int emailsPerMinute) {
        return new RedisRateLimiter(redis, enabled, uploadsPerMinute, emailsPerMinute);
    }

    @Bean
    @ConditionalOnMissingBean(RateLimiter.class)
    RateLimiter inMemoryRateLimiter(
            @Value("${myfinance.ratelimit.enabled:true}") boolean enabled,
            @Value("${myfinance.ratelimit.uploads-per-minute:60}") int uploadsPerMinute,
            @Value("${myfinance.ratelimit.emails-per-minute:30}") int emailsPerMinute) {
        return new InMemoryRateLimiter(enabled, uploadsPerMinute, emailsPerMinute);
    }
}
