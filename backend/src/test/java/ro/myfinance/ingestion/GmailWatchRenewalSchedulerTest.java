package ro.myfinance.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.ingestion.adapter.persistence.EmailConnectionRepository;
import ro.myfinance.ingestion.application.GmailMailboxClient;
import ro.myfinance.ingestion.application.GmailWatchRenewalScheduler;
import ro.myfinance.ingestion.domain.EmailConnection;

class GmailWatchRenewalSchedulerTest {

    private final JdbcTemplate admin = mock(JdbcTemplate.class);
    private final EmailConnectionRepository connections = mock(EmailConnectionRepository.class);
    private final GmailMailboxClient gmail = mock(GmailMailboxClient.class);
    private final GmailWatchRenewalScheduler scheduler =
            new GmailWatchRenewalScheduler(admin, connections, gmail);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void renewalKeepsTheUnprocessedHistoryCursor() {
        UUID tenantId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        EmailConnection connection = new EmailConnection(tenantId, "Inbox", "docs@example.com");
        connection.setGmailHistoryId("old-cursor");
        doReturn(List.of(new GmailWatchRenewalScheduler.ConnectionRef(connectionId, tenantId)))
                .when(admin).query(anyString(), any(RowMapper.class), any());
        when(connections.findById(connectionId)).thenReturn(Optional.of(connection));
        when(gmail.watch(connection)).thenReturn(new GmailMailboxClient.WatchRegistration(
                "new-current-history", Instant.now().plusSeconds(3600)));

        TenantContext.set(new TenantContext.Identity(tenantId, null, Role.TENANT_ADMIN, null));
        scheduler.renewDueWatches();

        assertThat(connection.getGmailHistoryId()).isEqualTo("old-cursor");
        assertThat(connection.getWatchExpiresAt()).isNotNull();
    }
}
