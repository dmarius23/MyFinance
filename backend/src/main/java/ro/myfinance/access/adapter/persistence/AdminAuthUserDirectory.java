package ro.myfinance.access.adapter.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ro.myfinance.access.application.AuthUserDirectory;

/**
 * The only implementation of {@link AuthUserDirectory}: a single, tightly-scoped query on the
 * RLS-bypassing admin pool (the migration role), mirroring how {@code GmailWebhookService} resolves a
 * mailbox to its tenant from a public webhook.
 *
 * <p>Selects <b>ids only</b>, filters out deactivated users in SQL, and lower-cases both sides so the
 * lookup is case-insensitive the way email is. Nothing else in this class — see the port's javadoc for why
 * that restraint matters.
 */
@Repository
public class AdminAuthUserDirectory implements AuthUserDirectory {

    private static final String SQL = """
            select id, tenant_id from app_user
             where lower(email) = lower(?) and status <> 'INACTIVE'
            """;

    private final JdbcTemplate admin;

    public AdminAuthUserDirectory(@Qualifier("adminJdbcTemplate") JdbcTemplate admin) {
        this.admin = admin;
    }

    @Override
    public List<TenantUser> findByEmailAcrossTenants(String email) {
        return admin.query(SQL,
                (rs, row) -> new TenantUser(
                        rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class)),
                email);
    }
}
