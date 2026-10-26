package ro.myfinance.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ro.myfinance.access.application.RepresentativeLinkDirectory;
import ro.myfinance.access.domain.RepresentativeLink;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.ingestion.adapter.persistence.EmailAttachmentRepository;
import ro.myfinance.ingestion.adapter.persistence.EmailConnectionRepository;
import ro.myfinance.ingestion.adapter.persistence.EmailMessageRepository;
import ro.myfinance.ingestion.application.EmailAttachmentIngestionService;
import ro.myfinance.ingestion.domain.EmailAttachment;
import ro.myfinance.ingestion.domain.EmailConnection;
import ro.myfinance.ingestion.domain.EmailMessage;
import ro.myfinance.intake.application.DocumentService;
import ro.myfinance.intake.domain.Document;
import ro.myfinance.notifications.application.NotificationService;

class EmailAttachmentIngestionServiceTest {

    private final EmailMessageRepository messages = mock(EmailMessageRepository.class);
    private final EmailAttachmentRepository attachments = mock(EmailAttachmentRepository.class);
    private final EmailConnectionRepository connections = mock(EmailConnectionRepository.class);
    private final RepresentativeLinkDirectory representatives = mock(RepresentativeLinkDirectory.class);
    private final DocumentService documents = mock(DocumentService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final EmailAttachmentIngestionService service =
            new EmailAttachmentIngestionService(messages, attachments, connections, representatives,
                    documents, notifications);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void importsAttachmentThroughTheNormalDocumentPipeline() {
        UUID tenantId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        TenantContext.set(new TenantContext.Identity(tenantId, UUID.randomUUID(),
                Role.TENANT_ADMIN, null));
        EmailConnection connection = new EmailConnection(tenantId, "Inbox", "docs@example.com");
        when(connections.findById(connection.getId())).thenReturn(Optional.of(connection));
        when(representatives.findByRepresentativeEmail("rep@example.com"))
                .thenReturn(List.of(new RepresentativeLink(tenantId, UUID.randomUUID(), companyId)));
        when(messages.findByConnectionIdAndGmailMessageId(connection.getId(), "gmail-1"))
                .thenReturn(Optional.empty());
        when(messages.save(any(EmailMessage.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(attachments.findByMessageIdAndGmailAttachmentId(any(), any())).thenReturn(Optional.empty());
        when(attachments.save(any(EmailAttachment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        Document document = mock(Document.class);
        UUID documentId = UUID.randomUUID();
        when(document.getId()).thenReturn(documentId);
        when(documents.upload(any(), any(), any(), any(), any(), any(), any())).thenReturn(document);

        var result = service.ingest(connection,
                new EmailAttachmentIngestionService.IncomingMessage("gmail-1", "thread-1",
                        "rep@example.com", "Extras", Instant.now(), List.of(
                                new EmailAttachmentIngestionService.IncomingAttachment(
                                        "att-1", "statement.pdf", "application/pdf",
                                        "%PDF-1.7".getBytes()))),
                LocalDate.of(2026, 9, 1));

        assertThat(result.imported()).isEqualTo(1);
        assertThat(result.duplicates()).isZero();
        assertThat(result.needsReview()).isZero();
    }

    @Test
    void rejectsConnectionFromAnotherTenant() {
        UUID currentTenant = UUID.randomUUID();
        TenantContext.set(new TenantContext.Identity(currentTenant, UUID.randomUUID(),
                Role.TENANT_ADMIN, null));
        EmailConnection foreignConnection = new EmailConnection(UUID.randomUUID(), "Inbox",
                "foreign@example.com");
        when(connections.findById(foreignConnection.getId())).thenReturn(Optional.of(foreignConnection));

        assertThatThrownBy(() -> service.ingest(foreignConnection,
                new EmailAttachmentIngestionService.IncomingMessage("gmail-foreign", null,
                        "rep@example.com", "Extras", Instant.now(), List.of()),
                LocalDate.of(2026, 9, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("current tenant");
    }

    @Test
    void marksMessageForReviewWhenDocumentImportFails() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(new TenantContext.Identity(tenantId, UUID.randomUUID(),
                Role.TENANT_ADMIN, null));
        EmailConnection connection = new EmailConnection(tenantId, "Inbox", "docs@example.com");
        when(connections.findById(connection.getId())).thenReturn(Optional.of(connection));
        UUID companyId = UUID.randomUUID();
        when(representatives.findByRepresentativeEmail("rep@example.com"))
                .thenReturn(List.of(new RepresentativeLink(tenantId, UUID.randomUUID(), companyId)));
        when(messages.findByConnectionIdAndGmailMessageId(connection.getId(), "gmail-failed"))
                .thenReturn(Optional.empty());
        when(messages.save(any(EmailMessage.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(attachments.findByMessageIdAndGmailAttachmentId(any(), any())).thenReturn(Optional.empty());
        when(attachments.save(any(EmailAttachment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(documents.upload(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("unsupported document"));

        var result = service.ingest(connection,
                new EmailAttachmentIngestionService.IncomingMessage("gmail-failed", null,
                        "rep@example.com", "Extras", Instant.now(), List.of(
                                new EmailAttachmentIngestionService.IncomingAttachment(
                                        "att-failed", "statement.pdf", "application/pdf",
                                        "%PDF-1.7".getBytes()))),
                LocalDate.of(2026, 9, 1));

        assertThat(result.imported()).isZero();
        assertThat(result.needsReview()).isEqualTo(1);
    }
}
