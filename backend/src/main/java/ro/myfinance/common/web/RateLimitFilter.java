package ro.myfinance.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;
import ro.myfinance.common.security.TenantContext;

/**
 * Rejects excessive document-upload and email-dispatch POSTs with HTTP 429 before they reach a controller.
 * Runs after {@link TenantContext} is bound so it can key the limit per tenant+user (falling back to the
 * remote address for the rare unauthenticated case). Only these two categories are limited; everything
 * else passes straight through.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private enum Category { UPLOAD, EMAIL }

    private final RateLimiter limiter;

    public RateLimitFilter(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Category category = categorize(request);
        if (category == null) {
            chain.doFilter(request, response);
            return;
        }
        String caller = caller(request);
        boolean allowed = category == Category.UPLOAD ? limiter.allowUpload(caller) : limiter.allowEmail(caller);
        if (allowed) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(429); // Too Many Requests
        response.setHeader("Retry-After", "60");
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"error\":\"rate_limited\",\"message\":\"Too many requests — please retry shortly.\"}");
    }

    private static Category categorize(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return null;
        }
        String path = request.getRequestURI();
        if (path.endsWith("/preview")) {
            return null; // email preview composes a draft, sends nothing
        }
        if (path.endsWith("/documents") || path.endsWith("/reclassify")) {
            return Category.UPLOAD;
        }
        if (path.endsWith("emails") || path.endsWith("/document-reminders")) {
            return Category.EMAIL;
        }
        return null;
    }

    private static String caller(HttpServletRequest request) {
        return TenantContext.current()
                .map(id -> id.tenantId() + ":" + id.userId())
                .orElseGet(request::getRemoteAddr);
    }
}
