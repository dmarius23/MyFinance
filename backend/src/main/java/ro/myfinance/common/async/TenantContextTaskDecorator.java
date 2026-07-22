package ro.myfinance.common.async;

import org.springframework.core.task.TaskDecorator;
import ro.myfinance.common.security.TenantContext;

/**
 * Propagates the {@link TenantContext} onto async worker threads. The request thread's identity is
 * captured when the task is submitted and re-bound on the executing thread, so background work
 * (document extraction, Drive mirroring) still runs under the right tenant — and therefore under the
 * right RLS scope via {@code RlsDataSource}. The binding is always cleared afterwards so identity never
 * leaks between pooled tasks.
 */
public class TenantContextTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        TenantContext.Identity captured = TenantContext.current().orElse(null);
        return () -> {
            TenantContext.Identity previous = TenantContext.current().orElse(null);
            if (captured != null) {
                TenantContext.set(captured);
            } else {
                TenantContext.clear();
            }
            try {
                runnable.run();
            } finally {
                if (previous != null) {
                    TenantContext.set(previous);
                } else {
                    TenantContext.clear();
                }
            }
        };
    }
}
