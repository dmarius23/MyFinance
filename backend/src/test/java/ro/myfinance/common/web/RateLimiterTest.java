package ro.myfinance.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RateLimiterTest {

    @Test
    void allowsUpToTheLimitThenBlocks() {
        RateLimiter limiter = new RateLimiter(true, 3, 3);
        assertThat(limiter.allowUpload("t:u")).isTrue();
        assertThat(limiter.allowUpload("t:u")).isTrue();
        assertThat(limiter.allowUpload("t:u")).isTrue();
        // Bucket exhausted; near-zero elapsed time means negligible refill.
        assertThat(limiter.allowUpload("t:u")).isFalse();
    }

    @Test
    void limitsArePerCallerAndPerCategory() {
        RateLimiter limiter = new RateLimiter(true, 1, 1);
        assertThat(limiter.allowUpload("tenantA:userA")).isTrue();
        assertThat(limiter.allowUpload("tenantA:userA")).isFalse();
        // A different caller has its own bucket.
        assertThat(limiter.allowUpload("tenantB:userB")).isTrue();
        // Email is a separate category from upload for the same caller.
        assertThat(limiter.allowEmail("tenantA:userA")).isTrue();
        assertThat(limiter.allowEmail("tenantA:userA")).isFalse();
    }

    @Test
    void disabledOrNonPositiveLimitAlwaysAllows() {
        RateLimiter disabled = new RateLimiter(false, 1, 1);
        assertThat(disabled.allowUpload("t:u")).isTrue();
        assertThat(disabled.allowUpload("t:u")).isTrue();

        RateLimiter unlimited = new RateLimiter(true, 0, 0);
        assertThat(unlimited.allowEmail("t:u")).isTrue();
        assertThat(unlimited.allowEmail("t:u")).isTrue();
    }
}
