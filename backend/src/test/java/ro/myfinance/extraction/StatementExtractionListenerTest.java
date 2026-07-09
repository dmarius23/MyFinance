package ro.myfinance.extraction;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.myfinance.extraction.adapter.persistence.BankStatementRepository;
import ro.myfinance.extraction.adapter.persistence.InvoiceRepository;
import ro.myfinance.extraction.application.BankStatementExtractionService;
import ro.myfinance.extraction.application.InvoiceExtractionService;
import ro.myfinance.extraction.application.StatementExtractionListener;
import ro.myfinance.intake.application.DocumentUploadedEvent;
import ro.myfinance.intake.domain.DocumentType;

@ExtendWith(MockitoExtension.class)
class StatementExtractionListenerTest {

    @Mock BankStatementExtractionService statements;
    @Mock InvoiceExtractionService invoices;
    @Mock BankStatementRepository statementRepo;
    @Mock InvoiceRepository invoiceRepo;
    @InjectMocks StatementExtractionListener listener;

    private DocumentUploadedEvent event(DocumentType type) {
        return new DocumentUploadedEvent(UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2026, 5, 1),
                type, "doc.pdf", new byte[] {1});
    }

    @Test
    void reclassifyingAwayFromStatementPurgesTheStaleStatement() {
        // A doc mis-classified as BANK_STATEMENT, then reclassified to UNCLASSIFIED: its stale statement
        // (and any invoice) must be removed, and neither extractor should run.
        DocumentUploadedEvent e = event(DocumentType.UNCLASSIFIED);

        listener.onDocumentUploaded(e);

        verify(statementRepo).deleteByDocumentId(e.documentId());
        verify(invoiceRepo).deleteByDocumentId(e.documentId());
        verifyNoInteractions(statements);
        verifyNoInteractions(invoices);
    }

    @Test
    void statementTypeExtractsAndClearsAnyPriorInvoice() {
        DocumentUploadedEvent e = event(DocumentType.BANK_STATEMENT);

        listener.onDocumentUploaded(e);

        verify(invoiceRepo).deleteByDocumentId(e.documentId());
        verify(statements).extract(e.documentId(), e.companyId(), e.periodMonth(), e.bytes());
        verify(statementRepo, never()).deleteByDocumentId(e.documentId());
    }

    @Test
    void invoiceTypeExtractsAndClearsAnyPriorStatement() {
        DocumentUploadedEvent e = event(DocumentType.INVOICE);

        listener.onDocumentUploaded(e);

        verify(statementRepo).deleteByDocumentId(e.documentId());
        verify(invoices).process(e.documentId(), e.companyId(), e.periodMonth(), e.filename(), e.bytes());
        verify(invoiceRepo, never()).deleteByDocumentId(e.documentId());
    }
}
