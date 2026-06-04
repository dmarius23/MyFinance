package ro.myfinance.access.adapter.external;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ro.myfinance.access.application.RepresentativeInviter;

/**
 * Fallback used when no Supabase service-role key is configured (local dev, tests). Mints a random
 * id and logs the intended invite instead of calling Supabase, so the whole flow is exercisable
 * without credentials. Selected by RepresentativeInviterConfig when Supabase is not configured.
 */
public class LoggingRepresentativeInviter implements RepresentativeInviter {

    private static final Logger log = LoggerFactory.getLogger(LoggingRepresentativeInviter.class);

    @Override
    public InvitedUser invite(String email, InviteClaims claims) {
        UUID id = UUID.randomUUID();
        log.info("[DEV INVITE] would invite {} as REPRESENTATIVE of company {} (tenant {}) -> {}",
                email, claims.companyId(), claims.tenantId(), id);
        return new InvitedUser(id);
    }
}
