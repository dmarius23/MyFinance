package ro.myfinance.common.outbox;

import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;

/**
 * Drains the transactional outbox with multi-worker single-flight. Each tick first reaps claims whose
 * worker died (visibility timeout → back to PENDING), then atomically <b>claims</b> a batch of due rows
 * ({@code FOR UPDATE SKIP LOCKED}, flipping them to PROCESSING so no other worker takes them), and delivers
 * each via {@link OutboxDelivery} (its own transaction), bound to the message's tenant.
 *
 * <p>The claim/reap run under a system identity ({@code role=SUPER_ADMIN}, no tenant) so the outbox RLS
 * policy's SUPER_ADMIN branch lets them span every tenant; each message is then processed bound to its own
 * tenant, so the handler's writes (and the outbox update) stay correctly tenant-scoped. {@link #relayDue(int)}
 * is the unit of work — the worker-profile {@link OutboxRelayScheduler} calls it on a fixed delay.
 */
@Component
public class OutboxRelay {

    private final OutboxClaimRepository claims;
    private final OutboxDelivery delivery;
    private final long visibilityTimeoutSeconds;

    public OutboxRelay(OutboxClaimRepository claims, OutboxDelivery delivery,
                       @Value("${myfinance.outbox.visibility-timeout-seconds:300}") long visibilityTimeoutSeconds) {
        this.claims = claims;
        this.delivery = delivery;
        this.visibilityTimeoutSeconds = visibilityTimeoutSeconds;
    }

    /** Reap stale claims, claim up to {@code batchSize} due messages, and deliver them. Returns how many were attempted. */
    public int relayDue(int batchSize) {
        // Preserve and restore the caller's binding (the inline drainer runs inside a request/test thread).
        TenantContext.Identity caller = TenantContext.current().orElse(null);
        try {
            List<OutboxClaimRepository.Claim> batch = runAsSystem(caller, () -> {
                claims.reapStale(visibilityTimeoutSeconds); // return crashed workers' orphans to PENDING first
                return claims.claimDue(batchSize);
            });
            int attempted = 0;
            for (OutboxClaimRepository.Claim claim : batch) {
                TenantContext.set(new TenantContext.Identity(claim.tenantId(), null, Role.SUPER_ADMIN, null));
                try {
                    delivery.deliverOne(claim.id());
                    attempted++;
                } finally {
                    restore(caller);
                }
            }
            return attempted;
        } finally {
            restore(caller);
        }
    }

    /** Run work under a system identity (so the cross-tenant claim/reap is permitted by RLS), then restore. */
    private <T> T runAsSystem(TenantContext.Identity restoreTo, Supplier<T> work) {
        TenantContext.set(new TenantContext.Identity(null, null, Role.SUPER_ADMIN, null));
        try {
            return work.get();
        } finally {
            restore(restoreTo);
        }
    }

    private static void restore(TenantContext.Identity identity) {
        if (identity != null) {
            TenantContext.set(identity);
        } else {
            TenantContext.clear();
        }
    }
}
