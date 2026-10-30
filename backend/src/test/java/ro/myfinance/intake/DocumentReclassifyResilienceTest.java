package ro.myfinance.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import ro.myfinance.common.audit.AuditRecorder;
import ro.myfinance.company.application.CompanyDirectory;
import ro.myfinance.intake.adapter.persistence.DocumentRepository;
import ro.myfinance.intake.application.DocumentClassifier;
import ro.myfinance.intake.application.DocumentReclassifier;
import ro.myfinance.intake.application.DocumentService;
import ro.myfinance.intake.application.DocumentStorage;
import ro.myfinance.intake.application.DocumentUploadedEvent;
import ro.myfinance.intake.application.DocumentValidator;
import ro.myfinance.intake.domain.Document;
import ro.myfinance.intake.domain.DocumentSource;
import ro.myfinance.intake.domain.DocumentStatus;
import ro.myfinance.intake.domain.DocumentType;

/**
 * Re-extraction is a recovery action — typically run precisely because something is already wrong — so a
 * document it cannot read must not take the rest of the month down with it.
 */
class DocumentReclassifyResilienceTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final LocalDate PERIOD = LocalDate.of(2026, 4, 1);

    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final DocumentStorage storage = mock(DocumentStorage.class);
    private final DocumentClassifier classifier = mock(DocumentClassifier.class);
    private final DocumentReclassifier ocr = mock(DocumentReclassifier.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private DocumentService service() {
        return new DocumentService(mock(CompanyDirectory.class), documents, storage, classifier, ocr,
                mock(DocumentValidator.class), mock(AuditRecorder.class), events,
                mock(ro.myfinance.tenant.application.TenantDirectory.class));
    }

    private static Document doc(String filename) {
        return new Document(UUID.randomUUID(), COMPANY, PERIOD, DocumentType.BANK_STATEMENT,
                DocumentSource.EMPLOYEE, DocumentStatus.UPLOADED, filename, "application/pdf",
                10, "key/" + filename, null);
    }

    /**
     * The blob fetch and the OCR classify both leave the database — a missing object or a rate-limited
     * OCR call throws. Before this, one such document aborted the whole sweep with an opaque 500 that
     * named nothing, so the statement everyone actually wanted re-parsed was never reached.
     */
    @Test
    void oneUnreadableDocumentDoesNotAbortTheRestOfTheMonth() {
        Document broken = doc("missing-blob.pdf");
        Document healthy = doc("libra-statement.pdf");
        when(documents.findByCompanyIdAndPeriodMonthOrderByUploadedAtDesc(COMPANY, PERIOD))
                .thenReturn(List.of(broken, healthy));
        when(storage.retrieve("key/missing-blob.pdf"))
                .thenThrow(new IllegalStateException("object not found"));
        when(storage.retrieve("key/libra-statement.pdf")).thenReturn("%PDF-1.4".getBytes());
        when(classifier.classify(any(), any(), any())).thenReturn(DocumentType.BANK_STATEMENT);

        DocumentService.ReclassifyResult result = service().reclassify(COMPANY, PERIOD);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.processed()).isEqualTo(1);
        // The readable document must still be re-published, otherwise the sweep achieved nothing.
        verify(events, times(1)).publishEvent(any(DocumentUploadedEvent.class));
    }

    /** A failing OCR classify is an outside call too, and must be survivable in the same way. */
    @Test
    void aFailingOcrClassifyIsSurvivable() {
        Document unclassified = doc("scan.pdf");
        unclassified.setType(DocumentType.UNCLASSIFIED);
        when(documents.findByCompanyIdAndPeriodMonthOrderByUploadedAtDesc(COMPANY, PERIOD))
                .thenReturn(List.of(unclassified));
        when(storage.retrieve(any())).thenReturn("%PDF-1.4".getBytes());
        when(classifier.classify(any(), any(), any())).thenReturn(DocumentType.UNCLASSIFIED);
        when(ocr.tryClassify(any(), any())).thenThrow(new RuntimeException("429 rate limited"));

        DocumentService.ReclassifyResult result = service().reclassify(COMPANY, PERIOD);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.processed()).isZero();
    }

    @Test
    void reportsCountsWhenEverythingIsReadable() {
        when(documents.findByCompanyIdAndPeriodMonthOrderByUploadedAtDesc(COMPANY, PERIOD))
                .thenReturn(List.of(doc("a.pdf"), doc("b.pdf")));
        when(storage.retrieve(any())).thenReturn("%PDF-1.4".getBytes());
        when(classifier.classify(any(), any(), any())).thenReturn(DocumentType.BANK_STATEMENT);
        when(ocr.tryClassify(any(), any())).thenReturn(Optional.empty());

        DocumentService.ReclassifyResult result = service().reclassify(COMPANY, PERIOD);

        assertThat(result.processed()).isEqualTo(2);
        assertThat(result.failed()).isZero();
        assertThatCode(() -> service().reclassify(COMPANY, PERIOD)).doesNotThrowAnyException();
    }
}
