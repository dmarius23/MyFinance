package ro.myfinance.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.ingestion.adapter.persistence.EmailConnectionRepository;
import ro.myfinance.ingestion.adapter.persistence.EmailSyncClaimRepository;
import ro.myfinance.ingestion.application.EmailAttachmentIngestionService;
import ro.myfinance.ingestion.application.GmailHistoryExpiredException;
import ro.myfinance.ingestion.application.GmailIngestionService;
import ro.myfinance.ingestion.application.GmailMailboxClient;
import ro.myfinance.ingestion.domain.EmailConnection;

class GmailIngestionServiceTest {

    private final EmailConnectionRepository connections = mock(EmailConnectionRepository.class);
    private final EmailSyncClaimRepository claims = mock(EmailSyncClaimRepository.class);
    private final GmailMailboxClient gmail = mock(GmailMailboxClient.class);
    private final EmailAttachmentIngestionService attachments = mock(EmailAttachmentIngestionService.class);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void expiredHistoryRequiresBackfillWithoutAdvancingCursor() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(new TenantContext.Identity(tenantId, UUID.randomUUID(),
                Role.TENANT_ADMIN, null));
        EmailConnection connection = new EmailConnection(tenantId, "Mailbox", "mailbox@example.com");
        connection.setGmailHistoryId("safe-cursor");
        when(connections.findById(connection.getId())).thenReturn(Optional.of(connection));
        when(claims.tryClaim(any(), any(), any())).thenReturn(true);
        when(gmail.changes(connection, "safe-cursor"))
                .thenThrow(new GmailHistoryExpiredException("expired", null));

        GmailIngestionService.SyncResult result = new GmailIngestionService(
                connections, claims, gmail, attachments).sync(connection.getId(), "new-cursor");

        assertThat(result.backfillRequired()).isTrue();
        assertThat(result.needsReview()).isEqualTo(1);
        assertThat(connection.getGmailHistoryId()).isEqualTo("safe-cursor");
        assertThat(connection.getStatus()).isEqualTo("NEEDS_REVIEW");
        verify(claims).release(connection.getId());
    }

    @Test
    void successfulFakeGmailSyncImportsAndAdvancesCursorLast() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(new TenantContext.Identity(tenantId, UUID.randomUUID(),
                Role.TENANT_ADMIN, null));
        EmailConnection connection = new EmailConnection(tenantId, "Mailbox", "mailbox@example.com");
        connection.setGmailHistoryId("10");
        when(connections.findById(connection.getId())).thenReturn(Optional.of(connection));
        when(claims.tryClaim(any(), any(), any())).thenReturn(true);
        GmailMailboxClient.AttachmentRef attachment = new GmailMailboxClient.AttachmentRef(
                "attachment-1", "statement.pdf", "application/pdf", null);
        GmailMailboxClient.MessageRef message = new GmailMailboxClient.MessageRef(
                "message-1", "thread-1", "sender@example.com", "Statement",
                Instant.parse("2026-09-01T10:00:00Z"), List.of(attachment));
        when(gmail.changes(connection, "10")).thenReturn(List.of(message));
        when(gmail.currentHistoryId(connection)).thenReturn("12");
        when(gmail.attachment(connection, message, attachment)).thenReturn(new byte[] {1, 2, 3});
        when(attachments.ingest(any(), any(), any()))
                .thenReturn(new EmailAttachmentIngestionService.Result(UUID.randomUUID(), 1, 0, 0));

        GmailIngestionService.SyncResult result = new GmailIngestionService(
                connections, claims, gmail, attachments).sync(connection.getId(), "12");

        assertThat(result.imported()).isEqualTo(1);
        assertThat(connection.getGmailHistoryId()).isEqualTo("12");
        assertThat(connection.getStatus()).isEqualTo("ACTIVE");
        // syncClaimed persists twice by design: once to flag SYNCING, once with the final ACTIVE state
        // and the advanced cursor. The end state is asserted above, so don't pin the exact save count.
        verify(connections, atLeastOnce()).save(connection);
        verify(claims).release(connection.getId());
    }

    @Test
    void recoverAfterReconnectBackfillsFromLastSyncedAt() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(new TenantContext.Identity(tenantId, UUID.randomUUID(),
                Role.TENANT_ADMIN, null));
        EmailConnection connection = new EmailConnection(tenantId, "Mailbox", "mailbox@example.com");
        Instant lastSynced = Instant.parse("2026-11-10T08:00:00Z");
        connection.setLastSyncedAt(lastSynced);
        when(connections.findById(connection.getId())).thenReturn(Optional.of(connection));
        when(claims.tryClaim(any(), any(), any())).thenReturn(true);
        when(gmail.watch(connection)).thenReturn(new GmailMailboxClient.WatchRegistration(
                "watch-cursor", Instant.parse("2026-11-29T00:00:00Z")));
        when(gmail.backfill(any(), any(), any())).thenReturn(List.of());
        when(gmail.changes(connection, "watch-cursor")).thenReturn(List.of());
        when(gmail.currentHistoryId(connection)).thenReturn("20");

        new GmailIngestionService(connections, claims, gmail, attachments)
                .recoverAfterReconnect(connection.getId());

        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        verify(gmail).backfill(any(), from.capture(), to.capture());
        assertThat(from.getValue()).isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(to.getValue()).isEqualTo(LocalDate.now(ZoneOffset.UTC).plusDays(1));
        assertThat(connection.getGmailHistoryId()).isEqualTo("20");
        assertThat(connection.getStatus()).isEqualTo("ACTIVE");
        verify(claims).release(connection.getId());
    }

    @Test
    void recoverAfterReconnectDefaultsToThirtyDaysWhenNeverSynced() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(new TenantContext.Identity(tenantId, UUID.randomUUID(),
                Role.TENANT_ADMIN, null));
        EmailConnection connection = new EmailConnection(tenantId, "Mailbox", "mailbox@example.com");
        when(connections.findById(connection.getId())).thenReturn(Optional.of(connection));
        when(claims.tryClaim(any(), any(), any())).thenReturn(true);
        when(gmail.watch(connection)).thenReturn(new GmailMailboxClient.WatchRegistration(
                "watch-cursor", Instant.parse("2026-11-29T00:00:00Z")));
        when(gmail.backfill(any(), any(), any())).thenReturn(List.of());
        when(gmail.changes(connection, "watch-cursor")).thenReturn(List.of());
        when(gmail.currentHistoryId(connection)).thenReturn("21");

        new GmailIngestionService(connections, claims, gmail, attachments)
                .recoverAfterReconnect(connection.getId());

        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        verify(gmail).backfill(any(), from.capture(), to.capture());
        assertThat(from.getValue()).isEqualTo(to.getValue().minusDays(30));
        verify(claims).release(connection.getId());
    }

    @Test
    void recoverAfterReconnectCapsLookbackAtNinetyDays() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(new TenantContext.Identity(tenantId, UUID.randomUUID(),
                Role.TENANT_ADMIN, null));
        EmailConnection connection = new EmailConnection(tenantId, "Mailbox", "mailbox@example.com");
        connection.setLastSyncedAt(Instant.parse("2025-01-01T00:00:00Z"));
        when(connections.findById(connection.getId())).thenReturn(Optional.of(connection));
        when(claims.tryClaim(any(), any(), any())).thenReturn(true);
        when(gmail.watch(connection)).thenReturn(new GmailMailboxClient.WatchRegistration(
                "watch-cursor", Instant.parse("2026-11-29T00:00:00Z")));
        when(gmail.backfill(any(), any(), any())).thenReturn(List.of());
        when(gmail.changes(connection, "watch-cursor")).thenReturn(List.of());
        when(gmail.currentHistoryId(connection)).thenReturn("22");

        new GmailIngestionService(connections, claims, gmail, attachments)
                .recoverAfterReconnect(connection.getId());

        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        verify(gmail).backfill(any(), from.capture(), to.capture());
        assertThat(from.getValue()).isEqualTo(to.getValue().minusDays(90));
        verify(claims).release(connection.getId());
    }
}
