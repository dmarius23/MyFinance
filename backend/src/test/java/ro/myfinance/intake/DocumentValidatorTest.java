package ro.myfinance.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ro.myfinance.company.domain.Company;
import ro.myfinance.extraction.application.BankStatementParser;
import ro.myfinance.extraction.application.BankStatementParserRegistry;
import ro.myfinance.extraction.application.InvoiceExtractor;
import ro.myfinance.extraction.application.ParsedInvoice;
import ro.myfinance.extraction.application.ParsedStatement;
import ro.myfinance.extraction.application.ParsedTransaction;
import ro.myfinance.intake.adapter.persistence.DocumentRepository;
import ro.myfinance.intake.application.DocumentValidator;
import ro.myfinance.intake.domain.DocumentType;
import ro.myfinance.intake.domain.DriveBlockReason;
import ro.myfinance.taxpayments.application.AnafDeclarationExtractor;

/** The one common validator: duplicate / wrong-company / wrong-period across every document type, fail-open. */
class DocumentValidatorTest {

    private static final LocalDate APRIL = LocalDate.of(2026, 4, 1);
    private static final LocalDate JUNE = LocalDate.of(2026, 6, 1);

    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final InvoiceExtractor invoices = mock(InvoiceExtractor.class);
    private final BankStatementParserRegistry statements = mock(BankStatementParserRegistry.class);
    // Real ANAF extractor (deterministic) against the checked-in fixtures.
    private final DocumentValidator validator =
            new DocumentValidator(documents, new AnafDeclarationExtractor(), invoices, statements);

    private static Company company(String cui, String name) {
        Company c = mock(Company.class);
        when(c.getId()).thenReturn(UUID.randomUUID());
        when(c.getCui()).thenReturn(cui);
        when(c.getLegalName()).thenReturn(name);
        return c;
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = DocumentValidatorTest.class.getResourceAsStream("/fixtures/anaf/" + name)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    @Test
    void identical_bytes_are_a_duplicate_for_any_type() {
        when(documents.existsByCompanyIdAndPeriodMonthAndTypeAndContentSha256(any(), any(), any(), any()))
                .thenReturn(true);
        var r = validator.validate(company("49443957", "ACME SRL"), JUNE, DocumentType.TRIAL_BALANCE,
                "balanta.pdf", "application/pdf", new byte[]{1, 2, 3}, null);
        assertThat(r.blockReason()).isEqualTo(DriveBlockReason.DUPLICATE);
        assertThat(r.contentSha256()).isNotBlank();
    }

    @Test
    void declaration_for_another_cui_is_wrong_company() throws IOException {
        byte[] d300 = fixture("D300.pdf"); // MERIC SRL, CUI 20464846, period April 2026
        org.junit.jupiter.api.Assumptions.assumeTrue(d300 != null, "fixture missing");
        var r = validator.validate(company("49443957", "ACME SRL"), APRIL, DocumentType.DECLARATION,
                "D300.pdf", "application/pdf", d300, null);
        assertThat(r.blockReason()).isEqualTo(DriveBlockReason.WRONG_COMPANY);
        assertThat(r.declKind()).isEqualTo("D300");
    }

    @Test
    void declaration_filed_under_the_wrong_month_is_wrong_period() throws IOException {
        byte[] d300 = fixture("D300.pdf");
        org.junit.jupiter.api.Assumptions.assumeTrue(d300 != null, "fixture missing");
        // Correct CUI (so not wrong-company) but filed under June while the declaration is for April.
        var r = validator.validate(company("20464846", "MERIC SRL"), JUNE, DocumentType.DECLARATION,
                "D300.pdf", "application/pdf", d300, null);
        assertThat(r.blockReason()).isEqualTo(DriveBlockReason.WRONG_PERIOD);
    }

    @Test
    void declaration_correct_company_and_month_is_eligible() throws IOException {
        byte[] d300 = fixture("D300.pdf");
        org.junit.jupiter.api.Assumptions.assumeTrue(d300 != null, "fixture missing");
        var r = validator.validate(company("20464846", "MERIC SRL"), APRIL, DocumentType.DECLARATION,
                "D300.pdf", "application/pdf", d300, null);
        assertThat(r.blocked()).isFalse();
        assertThat(r.declKind()).isEqualTo("D300");
    }

    @Test
    void invoice_on_neither_party_is_wrong_company() {
        when(invoices.extract(any(), any()))
                .thenReturn(new ParsedInvoice("Supplier", null, BigDecimal.TEN, JUNE, "222", "F1", "111"));
        var r = validator.validate(company("999", "ACME SRL"), JUNE, DocumentType.INVOICE,
                "f.pdf", "application/pdf", new byte[]{1}, null);
        assertThat(r.blockReason()).isEqualTo(DriveBlockReason.WRONG_COMPANY);
    }

    @Test
    void invoice_from_another_month_is_wrong_period() {
        // Issuer matches the company (not wrong-company), but the invoice date is March, filed under June.
        when(invoices.extract(any(), any()))
                .thenReturn(new ParsedInvoice("ACME", null, BigDecimal.TEN, LocalDate.of(2026, 3, 20), "222", "F2", "999"));
        var r = validator.validate(company("999", "ACME SRL"), JUNE, DocumentType.INVOICE,
                "f.pdf", "application/pdf", new byte[]{1}, null);
        assertThat(r.blockReason()).isEqualTo(DriveBlockReason.WRONG_PERIOD);
    }

    @Test
    void invoice_that_cannot_be_parsed_fails_open() {
        when(invoices.extract(any(), any())).thenThrow(new RuntimeException("scanned"));
        var r = validator.validate(company("999", "ACME SRL"), JUNE, DocumentType.INVOICE,
                "f.pdf", "application/pdf", new byte[]{1}, null);
        assertThat(r.blocked()).isFalse();
    }

    @Test
    void bank_statement_from_another_month_is_wrong_period() {
        // The statement IS for this company (its name is in the text), so the company check passes and the
        // wrong MONTH is what gets flagged.
        String text = "Extras de cont — Titular ACME SRL CUI 999";
        BankStatementParser parser = mock(BankStatementParser.class);
        when(statements.extractText(any())).thenReturn(text);
        when(statements.find(text)).thenReturn(Optional.of(parser));
        when(parser.parse(text)).thenReturn(new ParsedStatement("BT", "RO49", BigDecimal.ZERO, BigDecimal.TEN,
                List.of(new ParsedTransaction(LocalDate.of(2026, 3, 5), BigDecimal.TEN, null, null, "x", null, null))));
        var r = validator.validate(company("999", "ACME SRL"), JUNE, DocumentType.BANK_STATEMENT,
                "extras.pdf", "application/pdf", new byte[]{1}, null);
        assertThat(r.blockReason()).isEqualTo(DriveBlockReason.WRONG_PERIOD);
    }

    @Test
    void bank_statement_for_another_company_is_wrong_company() {
        // The statement text carries a different holder — neither this company's name nor CUI appears.
        when(statements.extractText(any())).thenReturn("Extras de cont — Titular OTHER COMPANY SRL CUI 123456");
        var r = validator.validate(company("999", "ACME SRL"), JUNE, DocumentType.BANK_STATEMENT,
                "extras.pdf", "application/pdf", new byte[]{1}, null);
        assertThat(r.blockReason()).isEqualTo(DriveBlockReason.WRONG_COMPANY);
    }

    @Test
    void payroll_filename_month_mismatch_is_wrong_period() {
        // text/plain → the PDF company check is skipped; the filename carries the (mismatched) month.
        var r = validator.validate(company("999", "ACME SRL"), JUNE, DocumentType.PAYROLL,
                "stat_salarii_2026_03.pdf", "text/plain", new byte[]{1}, null);
        assertThat(r.blockReason()).isEqualTo(DriveBlockReason.WRONG_PERIOD);
    }

    @Test
    void unreadable_declaration_bytes_fail_open() {
        var r = validator.validate(company("999", "ACME SRL"), JUNE, DocumentType.DECLARATION,
                "junk.pdf", "application/pdf", new byte[]{9, 9, 9}, null);
        assertThat(r.blocked()).isFalse();
    }
}
