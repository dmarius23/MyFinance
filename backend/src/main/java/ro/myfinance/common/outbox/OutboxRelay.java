package ro.myfinance.common.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;

/**
 * Drains the transactional outbox: finds due PENDING {@link OutboxMessage}s across all tenants and
 * delivers each via {@link OutboxDelivery} (its own transaction), bound to the message's tenant.
 *
 * <p>The cross-tenant scan runs under a system identity ({@code role=SUPER_ADMIN}, no tenant) so the
 * outbox RLS policy's SUPER_ADMIN branch lets it see every tenant's rows; each message is then processed
 * bound to its own tenant, so the handler's writes (and the outbox update) stay correctly tenant-scoped.
 * {@link #relayDue(int)} is the unit of work — the worker-profile {@link OutboxRelayScheduler} calls it on
 * a fixed delay.
 */
@Component
public class OutboxRelay {

    private final OutboxMessageRepository outbox;
    private final OutboxDelivery delivery;

    public OutboxRelay(OutboxMessageRepository outbox, OutboxDelivery delivery) {
        this.outbox = outbox;
        this.delivery = delivery;
    }

    /** Deliver up to {@code batchSize} due messages. Returns how many were attempted. */
    public int relayDue(int batchSize) {
        // Preserve and restore the caller's binding (the inline drainer runs inside a request/test thread).
        TenantContext.Identity caller = TenantContext.current().orElse(null);
        try {
            List<UUID> dueIds = runAsSystem(caller, () ->
                    outbox.findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                                    OutboxMessage.Status.PENDING, Instant.now(), Limit.of(batchSize))
                            .stream().map(OutboxMessage::getId).toList());
            int attempted = 0;
            for (UUID id : dueIds) {
                UUID tenantId = runAsSystem(caller,
                        () -> outbox.findById(id).map(OutboxMessage::getTenantId).orElse(null));
                if (tenantId == null) {
                    continue;
                }
                TenantContext.set(new TenantContext.Identity(tenantId, null, Role.SUPER_ADMIN, null));
                try {
                    delivery.deliverOne(id);
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

    /** Run a read under a system identity (so the cross-tenant outbox scan is permitted by RLS), then restore. */
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
