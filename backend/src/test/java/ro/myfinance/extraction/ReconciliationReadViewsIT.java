package ro.myfinance.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.company.application.CompanyService;
import ro.myfinance.extraction.adapter.persistence.InvoiceRepository;
import ro.myfinance.extraction.application.BankStatementParser;
import ro.myfinance.extraction.application.ParsedStatement;
import ro.myfinance.extraction.application.ParsedTransaction;
import ro.myfinance.extraction.application.ReconciliationService;
import ro.myfinance.intake.application.DocumentService;
import ro.myfinance.support.AbstractPostgresIT;

/**
 * Characterization tests for the read/reporting surface extracted in S11 — the one-click match
 * {@link ReconciliationService#suggestions} engine ({@code MatchSuggester}) and the per-document warning
 * flags of {@link ReconciliationService#documentStatuses} ({@code ReconciliationView}). Kept in its own
 * class (fresh companies per test) so it doesn't perturb the method order of {@code ReconciliationServiceIT}.
 */
@Import(ReconciliationReadViewsIT.StubConfig.class)
class ReconciliationReadViewsIT extends AbstractPostgresIT {

    private static final UUID TENANT = UUID.fromString("cccccccc-0000-0000-0000-0000000000e3");

    @Autowired DocumentService documents;
    @Autowired CompanyService companies;
    @Autowired ReconciliationService reconciliation;
    @Autowired JdbcTemplate jdbc;
    @Autowired InvoiceRepository invoiceRepo;

    @AfterEach
    void clear() { TenantContext.clear(); }

    /** A parser returning a fixed statement (supplier debit + treasury debit + incoming credit). */
    @TestConfiguration
    static class StubConfig {
        @Bean
        @Order(0)
        BankStatementParser stub() {
            return new BankStatementParser() {
                @Override public boolean supports(String text) { return text.contains("RECONSTUB"); }
                @Override public ParsedStatement parse(String t) {
                    return new ParsedStatement("STUB", "RO00OWN", new BigDecimal("1000.00"),
                            new BigDecimal("1170.00"), List.of(
                            new ParsedTransaction(LocalDate.of(2026, 6, 3), new BigDecimal("-200.00"),
                                    "SELGROS", "RO21SUPP", "achizitie marfa", "r1", new BigDecimal("800.00")),
                            new ParsedTransaction(LocalDate.of(2026, 6, 4), new BigDecimal("-30.00"),
                                    "Trezoreria Cluj", "RO54TREZ21620A470300", "CAM", "r2", new BigDecimal("770.00")),
                            new ParsedTransaction(LocalDate.of(2026, 6, 5), new BigDecimal("400.00"),
                                    "AROBIS", "RO11CLI", "incasare", "r3", new BigDecimal("1170.00"))));
                }
            };
        }
    }

    private static byte[] pdf(String marker) throws Exception {
        try (var d = new org.apache.pdfbox.pdmodel.PDDocument()) {
            var p = new org.apache.pdfbox.pdmodel.PDPage(); d.addPage(p);
            try (var cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(d, p)) {
                cs.beginText();
                cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(marker + " extras de cont");
                cs.endText();
            }
            var out = new java.io.ByteArrayOutputStream(); d.save(out); return out.toByteArray();
        }
    }

    private UUID asTenantWithCompany() {
        TenantContext.set(new TenantContext.Identity(TENANT, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        jdbc.update("insert into tenant(id, name, status, plan) values (?, ?, 'ACTIVE', 'STANDARD') on conflict do nothing",
                TENANT, "T-" + TENANT);
        return companies.create("Client SRL", "RO-RV-" + UUID.randomUUID(), "SRL", "Cluj", null, null, null, null, null).getId();
    }

    private UUID seedInvoice(UUID companyId, String supplierName, String iban, String amount, LocalDate date) {
        UUID docId = UUID.randomUUID();
        jdbc.update("insert into document(id, tenant_id, company_id, period_month, type, source, status, "
                + "original_filename, content_type, size_bytes, storage_key) "
                + "values (?,?,?,?, 'INVOICE','EMPLOYEE','UPLOADED','inv.pdf','application/pdf',1,'k/" + docId + "')",
                docId, TENANT, companyId, java.sql.Date.valueOf(LocalDate.of(2026, 6, 1)));
        var inv = invoiceRepo.save(new ro.myfinance.extraction.domain.Invoice(
                TENANT, docId, companyId, LocalDate.of(2026, 6, 1), supplierName, iban,
                new BigDecimal(amount), date, "inv.pdf", "EXTRACTED"));
        return inv.getId();
    }

    @Test
    void suggestsExactCrossPeriodMatchBySupplierIban() throws Exception {
        UUID companyId = asTenantWithCompany();
        documents.upload(companyId, LocalDate.of(2026, 6, 1), "extras.pdf", "application/pdf", pdf("RECONSTUB"));
        // The SELGROS debit (-200, IBAN RO21SUPP) is left unmatched (no matchPeriod run); an open invoice
        // on the same supplier IBAN for the same remaining is surfaced as a one-click EXACT suggestion.
        seedInvoice(companyId, "ACME", "RO21SUPP", "200.00", LocalDate.of(2026, 6, 1));

        var suggestions = reconciliation.suggestions(companyId, LocalDate.of(2026, 6, 1));
        assertThat(suggestions).anySatisfy(s -> {
            assertThat(s.kind()).isEqualTo("EXACT");
            assertThat(s.links()).hasSize(1);
            assertThat(s.links().get(0).partnerName()).isEqualTo("SELGROS");
            assertThat(s.links().get(0).amount()).isEqualByComparingTo(new BigDecimal("200.00"));
        });
    }

    @Test
    void documentStatusesFlagsInvoiceDatedOutsidePeriodAsRed() throws Exception {
        UUID companyId = asTenantWithCompany();
        documents.upload(companyId, LocalDate.of(2026, 6, 1), "extras.pdf", "application/pdf", pdf("RECONSTUB"));
        // Filed under June but the extracted invoice date lands in July → RED "date outside period", UNPAID.
        UUID invId = seedInvoice(companyId, "ACME", "RO99XYZ", "75.00", LocalDate.of(2026, 7, 15));
        UUID invDocId = invoiceRepo.findById(invId).orElseThrow().getDocumentId();

        var statuses = reconciliation.documentStatuses(companyId, LocalDate.of(2026, 6, 1));
        // The statement (all three transactions in June) carries no date flag.
        assertThat(statuses).anySatisfy(s -> assertThat(s.dateFlag()).isNull());
        assertThat(statuses).anySatisfy(s -> {
            assertThat(s.documentId()).isEqualTo(invDocId);
            assertThat(s.dateFlag()).isEqualTo("RED");
            assertThat(s.dateReason()).isEqualTo("date_outside_period");
            assertThat(s.paymentStatus()).isEqualTo("UNPAID");
        });
    }
}
