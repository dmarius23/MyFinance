package ro.myfinance.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ro.myfinance.company.application.CompanyDirectory;
import ro.myfinance.company.domain.Company;
import ro.myfinance.intake.adapter.persistence.DocumentRepository;
import ro.myfinance.intake.application.DocumentMirrorListener;
import ro.myfinance.intake.application.DocumentUploadedEvent;
import ro.myfinance.intake.application.DriveDocumentWriter;
import ro.myfinance.intake.application.DriveStorageTarget;
import ro.myfinance.intake.domain.Document;
import ro.myfinance.intake.domain.DocumentSource;
import ro.myfinance.intake.domain.DocumentStatus;
import ro.myfinance.intake.domain.DocumentType;
import ro.myfinance.intake.domain.DriveBlockReason;

/** The Drive mirror is gated: it skips blocked / Drive-sourced documents and files the rest by content. */
class DocumentMirrorListenerTest {

    private static final UUID DOC = UUID.randomUUID();
    private static final UUID COMPANY = UUID.randomUUID();
    private static final LocalDate JUNE = LocalDate.of(2026, 6, 1);

    private final DriveStorageTarget target = mock(DriveStorageTarget.class);
    private final DriveDocumentWriter writer = mock(DriveDocumentWriter.class);
    private final CompanyDirectory companies = mock(CompanyDirectory.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final DocumentMirrorListener listener =
            new DocumentMirrorListener(target, writer, companies, documents);

    @BeforeEach
    void setup() {
        when(writer.isEnabled()).thenReturn(true);
        when(target.writeTargetFor(any()))
                .thenReturn(Optional.of(new DriveStorageTarget.Target("root", "root", false)));
        when(writer.put(any(), any(), any(), any(), any(), any(), any())).thenReturn("drive-file-id");
        Company c = mock(Company.class);
        when(c.getLegalName()).thenReturn("ACME SRL");
        when(companies.findById(COMPANY)).thenReturn(Optional.of(c));
    }

    private Document doc(DocumentType type, DocumentSource source, DriveBlockReason block, String declKind, String cod) {
        Document d = new Document(UUID.randomUUID(), COMPANY, JUNE, type, source, DocumentStatus.UPLOADED,
                "f.pdf", "application/pdf", 3, "key", null);
        d.setDriveBlockReason(block);
        d.setDeclKind(declKind);
        d.setDominantObligationCod(cod);
        when(documents.findById(DOC)).thenReturn(Optional.of(d));
        return d;
    }

    private void fire(DocumentType type) {
        listener.onUploaded(new DocumentUploadedEvent(DOC, COMPANY, JUNE, type, "f.pdf", new byte[]{1}));
    }

    @Test
    void a_blocked_document_is_not_mirrored() {
        doc(DocumentType.INVOICE, DocumentSource.EMPLOYEE, DriveBlockReason.DUPLICATE, null, null);
        fire(DocumentType.INVOICE);
        verify(writer, never()).put(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void a_drive_sourced_document_is_not_mirrored_back() {
        doc(DocumentType.BANK_STATEMENT, DocumentSource.DRIVE, null, null, null);
        fire(DocumentType.BANK_STATEMENT);
        verify(writer, never()).put(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void a_clean_bank_statement_files_under_company_year_month() {
        doc(DocumentType.BANK_STATEMENT, DocumentSource.EMPLOYEE, null, null, null);
        fire(DocumentType.BANK_STATEMENT);
        assertThat(capturedSegments()).containsExactly("ACME SRL", "2026", "6. Iunie");
    }

    @Test
    void a_clean_declaration_uses_its_captured_form_folder() {
        doc(DocumentType.DECLARATION, DocumentSource.EMPLOYEE, null, "D112", null);
        fire(DocumentType.DECLARATION);
        assertThat(capturedSegments()).containsExactly("Declaratii 2026", "6. Iunie 2026", "D112");
    }

    @SuppressWarnings("unchecked")
    private List<String> capturedSegments() {
        ArgumentCaptor<List<String>> segs = ArgumentCaptor.forClass(List.class);
        verify(writer).put(eq("root"), eq("root"), segs.capture(), any(), any(), any(), eq(DOC));
        return segs.getValue();
    }
}
