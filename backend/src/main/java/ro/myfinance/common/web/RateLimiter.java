package ro.myfinance.common.web;

/**
 * Rate limiter for the expensive write endpoints (document upload and email dispatch), keyed per caller
 * (tenant+user). Two backends are wired by {@code RateLimiterConfig}: an in-memory token bucket
 * (per application instance, the default) and a Redis fixed-window limiter (one shared limit across all
 * instances, enabled with {@code myfinance.ratelimit.distributed=true}). Configure with
 * {@code myfinance.ratelimit.*}; a non-positive limit or {@code enabled=false} disables limiting.
 */
public interface RateLimiter {

    /** True if the caller may perform another upload now. */
    boolean allowUpload(String caller);

    /** True if the caller may trigger another email now. */
    boolean allowEmail(String caller);
}
