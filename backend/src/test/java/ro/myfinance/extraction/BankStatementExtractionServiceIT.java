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
import org.springframework.jdbc.core.JdbcTemplate;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.company.application.CompanyService;
import ro.myfinance.extraction.application.BankStatementParser;
import ro.myfinance.extraction.application.ParsedStatement;
import ro.myfinance.extraction.application.ParsedTransaction;
import ro.myfinance.extraction.domain.BankStatement;
import ro.myfinance.extraction.domain.StatementStatus;
import ro.myfinance.intake.application.DocumentService;
import ro.myfinance.support.AbstractPostgresIT;
import org.springframework.context.annotation.Import;

@Import(BankStatementExtractionServiceIT.StubParserConfig.class)
class BankStatementExtractionServiceIT extends AbstractPostgresIT {

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000b1");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000b2");

    @Autowired DocumentService documents;
    @Autowired CompanyService companies;
    @Autowired ro.myfinance.extraction.adapter.persistence.BankStatementRepository statements;
    @Autowired ro.myfinance.extraction.adapter.persistence.BankTransactionRepository transactions;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clear() { TenantContext.clear(); }

    /** A parser that recognizes the marker "STUBBANK" and returns two transactions that cross-check. */
    @TestConfiguration
    static class StubParserConfig {
        @Bean
        @org.springframework.core.annotation.Order(0)
        BankStatementParser stubParser() {
            return new BankStatementParser() {
                @Override public boolean supports(String text) { return text.contains("STUBBANK"); }
                @Override public ParsedStatement parse(String pdfText) {
                    return new ParsedStatement("STUB", "RO00STUB", new BigDecimal("100.00"),
                            new BigDecimal("70.00"), List.of(
                            new ParsedTransaction(LocalDate.of(2026, 6, 3), new BigDecimal("-50.00"),
                                    "Supplier SRL", "RO11", "achizitie", "r1", new BigDecimal("50.00")),
                            new ParsedTransaction(LocalDate.of(2026, 6, 4), new BigDecimal("20.00"),
                                    "Client SRL", "RO22", "incasare", "r2", new BigDecimal("70.00"))));
                }
            };
        }
    }

    private UUID asTenantWithCompany(UUID tenantId) {
        TenantContext.set(new TenantContext.Identity(tenantId, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        jdbc.update("insert into tenant(id, name, status, plan) values (?, ?, 'ACTIVE', 'STANDARD') on conflict do nothing",
                tenantId, "T-" + tenantId);
        return companies.create("Client SRL", "RO-BNK-" + UUID.randomUUID(), "SRL", "Cluj", null, null, null).getId();
    }

    // A tiny PDF whose text contains the STUBBANK marker.
    private static byte[] stubPdf() throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage();
            doc.addPage(page);
            try (var cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("STUBBANK extras de cont");
                cs.endText();
            }
            var out = new java.io.ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void parsesStatementOnUploadAndCrossChecks() throws Exception {
        UUID companyId = asTenantWithCompany(TENANT_A);
        var doc = documents.upload(companyId, LocalDate.of(2026, 6, 1), "extras.pdf", "application/pdf", stubPdf());

        List<BankStatement> st = statements.findByCompanyIdAndPeriodMonth(companyId, LocalDate.of(2026, 6, 1));
        assertThat(st).hasSize(1);
        assertThat(st.get(0).getStatus()).isEqualTo(StatementStatus.EXTRACTED);
        assertThat(st.get(0).isCrossCheckOk()).isTrue();
        assertThat(st.get(0).getTxnCount()).isEqualTo(2);
        assertThat(transactions.findByStatementIdInOrderByTxnDateDesc(List.of(st.get(0).getId()))).hasSize(2);
        // unrelated: ensure the document itself classified as BANK_STATEMENT (extras de cont marker)
        assertThat(doc.getType().name()).isEqualTo("BANK_STATEMENT");
    }

    @Test
    void unsupportedBankMarksNeedsReview() throws Exception {
        UUID companyId = asTenantWithCompany(TENANT_A);
        // A PDF without the STUBBANK marker but still classified a statement (contains "extras de cont").
        byte[] pdf;
        try (org.apache.pdfbox.pdmodel.PDDocument d = new org.apache.pdfbox.pdmodel.PDDocument()) {
            var p = new org.apache.pdfbox.pdmodel.PDPage(); d.addPage(p);
            try (var cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(d, p)) {
                cs.beginText();
                cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("Extras de cont BRD necunoscut");
                cs.endText();
            }
            var out = new java.io.ByteArrayOutputStream(); d.save(out); pdf = out.toByteArray();
        }
        documents.upload(companyId, LocalDate.of(2026, 6, 1), "x.pdf", "application/pdf", pdf);
        var st = statements.findByCompanyIdAndPeriodMonth(companyId, LocalDate.of(2026, 6, 1));
        assertThat(st).hasSize(1);
        assertThat(st.get(0).getStatus()).isEqualTo(StatementStatus.NEEDS_REVIEW);
    }

    @Test
    void tenantBCannotSeeTenantAStatements() throws Exception {
        UUID companyA = asTenantWithCompany(TENANT_A);
        documents.upload(companyA, LocalDate.of(2026, 6, 1), "extras.pdf", "application/pdf", stubPdf());
        asTenantWithCompany(TENANT_B);
        assertThat(statements.findByCompanyIdAndPeriodMonth(companyA, LocalDate.of(2026, 6, 1))).isEmpty();
    }
}
