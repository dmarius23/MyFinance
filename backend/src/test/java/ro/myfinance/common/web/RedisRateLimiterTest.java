package ro.myfinance.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisRateLimiterTest {

    @SuppressWarnings("unchecked")
    private static ValueOperations<String, String> stubOps(StringRedisTemplate redis) {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        return ops;
    }

    @Test
    void allowsWhileUnderTheSharedCountThenBlocks() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(stubOps(redis).increment(anyString())).thenReturn(1L, 2L, 3L, 4L);

        RateLimiter limiter = new RedisRateLimiter(redis, true, 3, 3);
        assertThat(limiter.allowUpload("t:u")).isTrue();  // shared count 1
        assertThat(limiter.allowUpload("t:u")).isTrue();  // 2
        assertThat(limiter.allowUpload("t:u")).isTrue();  // 3
        assertThat(limiter.allowUpload("t:u")).isFalse(); // 4 > limit 3
    }

    @Test
    void failsOpenWhenRedisErrors() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(stubOps(redis).increment(anyString())).thenThrow(new RuntimeException("redis down"));

        RateLimiter limiter = new RedisRateLimiter(redis, true, 1, 1);
        assertThat(limiter.allowEmail("t:u")).isTrue();
        assertThat(limiter.allowEmail("t:u")).isTrue();
    }

    @Test
    void disabledDoesNotTouchRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RateLimiter limiter = new RedisRateLimiter(redis, false, 1, 1);
        assertThat(limiter.allowUpload("t:u")).isTrue();
        verifyNoInteractions(redis);
    }
}
