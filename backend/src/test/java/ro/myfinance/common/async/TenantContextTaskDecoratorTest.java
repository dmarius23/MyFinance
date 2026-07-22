package ro.myfinance.common.async;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;

/**
 * The decorator must carry the submitting thread's tenant identity onto the worker thread (so background
 * document work stays under the right RLS scope) and always restore/clear afterwards so nothing leaks
 * between pooled tasks.
 */
class TenantContextTaskDecoratorTest {

    private final TenantContextTaskDecorator decorator = new TenantContextTaskDecorator();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void rebindsCapturedIdentityOnTheWorkerThreadThenClears() {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(new TenantContext.Identity(tenant, UUID.randomUUID(), Role.TENANT_ADMIN, null));

        AtomicReference<UUID> seenByTask = new AtomicReference<>();
        // Decorate at "submit" time (identity captured now), then simulate a fresh pool thread with no binding.
        Runnable decorated = decorator.decorate(
                () -> seenByTask.set(TenantContext.tenantId().orElse(null)));
        TenantContext.clear(); // the pool thread starts with nothing bound

        decorated.run();

        assertThat(seenByTask.get()).as("task ran under the captured tenant").isEqualTo(tenant);
        assertThat(TenantContext.current()).as("binding restored/cleared after the task").isEmpty();
    }

    @Test
    void clearsBindingWhenNothingWasCaptured() {
        TenantContext.clear(); // nothing bound at submit time

        AtomicReference<Boolean> boundInside = new AtomicReference<>();
        Runnable decorated = decorator.decorate(() -> boundInside.set(TenantContext.current().isPresent()));
        // Simulate a dirty pool thread that had a leftover binding.
        TenantContext.set(new TenantContext.Identity(UUID.randomUUID(), UUID.randomUUID(), Role.EMPLOYEE, null));

        decorated.run();

        assertThat(boundInside.get()).as("no identity captured → task runs unbound").isFalse();
    }
}
