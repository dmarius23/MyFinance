package ro.myfinance.common.web;

/**
 * Shared limit configuration and per-category dispatch for the {@link RateLimiter} backends. Subclasses
 * only implement how a single key is counted against its per-minute budget.
 */
abstract class AbstractRateLimiter implements RateLimiter {

    private final boolean enabled;
    private final int uploadsPerMinute;
    private final int emailsPerMinute;

    protected AbstractRateLimiter(boolean enabled, int uploadsPerMinute, int emailsPerMinute) {
        this.enabled = enabled;
        this.uploadsPerMinute = uploadsPerMinute;
        this.emailsPerMinute = emailsPerMinute;
    }

    @Override
    public boolean allowUpload(String caller) {
        return allow("upload", caller, uploadsPerMinute);
    }

    @Override
    public boolean allowEmail(String caller) {
        return allow("email", caller, emailsPerMinute);
    }

    private boolean allow(String category, String caller, int perMinute) {
        if (!enabled || perMinute <= 0) {
            return true;
        }
        return tryConsume(category + ":" + caller, perMinute);
    }

    /** @return true if a request keyed by {@code key} fits within its {@code perMinute} budget. */
    protected abstract boolean tryConsume(String key, int perMinute);
}
