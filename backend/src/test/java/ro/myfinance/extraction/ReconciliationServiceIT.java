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
import ro.myfinance.extraction.adapter.persistence.BankStatementRepository;
import ro.myfinance.extraction.adapter.persistence.BankTransactionRepository;
import ro.myfinance.extraction.application.BankStatementParser;
import ro.myfinance.extraction.application.ParsedStatement;
import ro.myfinance.extraction.application.ParsedTransaction;
import ro.myfinance.extraction.application.ReconciliationService;
import ro.myfinance.extraction.domain.BankStatement;
import ro.myfinance.extraction.domain.BankTransaction;
import ro.myfinance.extraction.domain.DecisionSource;
import ro.myfinance.extraction.domain.DocCategory;
import ro.myfinance.intake.application.DocumentService;
import ro.myfinance.support.AbstractPostgresIT;

@Import(ReconciliationServiceIT.StubConfig.class)
class ReconciliationServiceIT extends AbstractPostgresIT {

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000e1");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000e2");

    @Autowired DocumentService documents;
    @Autowired CompanyService companies;
    @Autowired ReconciliationService reconciliation;
    @Autowired BankStatementRepository statements;
    @Autowired BankTransactionRepository txns;
    @Autowired JdbcTemplate jdbc;

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

    private UUID asTenantWithCompany(UUID tenantId) {
        TenantContext.set(new TenantContext.Identity(tenantId, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        jdbc.update("insert into tenant(id, name, status, plan) values (?, ?, 'ACTIVE', 'STANDARD') on conflict do nothing",
                tenantId, "T-" + tenantId);
        return companies.create("Client SRL", "RO-REC-" + UUID.randomUUID(), "SRL", "Cluj", null, null, null).getId();
    }

    private List<BankTransaction> companyTxns(UUID companyId) {
        List<UUID> ids = statements.findByCompanyIdAndPeriodMonth(companyId, LocalDate.of(2026, 6, 1))
                .stream().map(BankStatement::getId).toList();
        return txns.findByStatementIdInOrderByTxnDateDesc(ids);
    }

    @Test
    void classifiesOnUpload() throws Exception {
        UUID companyId = asTenantWithCompany(TENANT_A);
        documents.upload(companyId, LocalDate.of(2026, 6, 1), "extras.pdf", "application/pdf", pdf("RECONSTUB"));

        List<BankTransaction> list = companyTxns(companyId);
        assertThat(list).hasSize(3);
        BankTransaction supplier = list.stream().filter(t -> "SELGROS".equals(t.getPartnerName())).findFirst().orElseThrow();
        assertThat(supplier.isRequiresDocument()).isTrue();
        assertThat(supplier.getCategory()).isEqualTo(DocCategory.SUPPLIER);
        assertThat(supplier.getDecisionSource()).isEqualTo(DecisionSource.SYSTEM_RULE);

        BankTransaction tax = list.stream().filter(t -> t.getCategory() == DocCategory.TAX).findFirst().orElseThrow();
        assertThat(tax.isRequiresDocument()).isFalse();
        BankTransaction income = list.stream().filter(t -> t.getCategory() == DocCategory.INCOME).findFirst().orElseThrow();
        assertThat(income.isRequiresDocument()).isFalse();
    }

    @Test
    void dedupAcrossReupload() throws Exception {
        UUID companyId = asTenantWithCompany(TENANT_A);
        documents.upload(companyId, LocalDate.of(2026, 6, 1), "extras.pdf", "application/pdf", pdf("RECONSTUB"));
        documents.upload(companyId, LocalDate.of(2026, 6, 1), "extras-again.pdf", "application/pdf", pdf("RECONSTUB"));
        assertThat(companyTxns(companyId)).hasSize(3); // not 6
    }

    @Test
    void overrideCreatesLearnedRuleAppliedToNextStatement() throws Exception {
        UUID companyId = asTenantWithCompany(TENANT_A);
        documents.upload(companyId, LocalDate.of(2026, 6, 1), "extras.pdf", "application/pdf", pdf("RECONSTUB"));
        BankTransaction supplier = companyTxns(companyId).stream()
                .filter(t -> "SELGROS".equals(t.getPartnerName())).findFirst().orElseThrow();

        reconciliation.setRequirement(supplier.getId(), false, "no doc needed");
        BankTransaction after = txns.findById(supplier.getId()).orElseThrow();
        assertThat(after.isRequiresDocument()).isFalse();
        assertThat(after.getDecisionSource()).isEqualTo(DecisionSource.ACCOUNTANT_SET);

        // A second statement for July with the same counterparty+description inherits the learned rule.
        documents.upload(companyId, LocalDate.of(2026, 7, 1), "iulie.pdf", "application/pdf", pdf("RECONSTUB"));
        List<UUID> julyIds = statements.findByCompanyIdAndPeriodMonth(companyId, LocalDate.of(2026, 7, 1))
                .stream().map(BankStatement::getId).toList();
        BankTransaction julySupplier = txns.findByStatementIdInOrderByTxnDateDesc(julyIds).stream()
                .filter(t -> "SELGROS".equals(t.getPartnerName())).findFirst().orElseThrow();
        assertThat(julySupplier.isRequiresDocument()).isFalse();
        assertThat(julySupplier.getDecisionSource()).isEqualTo(DecisionSource.LEARNED_RULE);
    }

    @Test
    void completenessReflectsMissingDocs() throws Exception {
        UUID companyId = asTenantWithCompany(TENANT_A);
        documents.upload(companyId, LocalDate.of(2026, 6, 1), "extras.pdf", "application/pdf", pdf("RECONSTUB"));
        var summary = reconciliation.completenessSummary(LocalDate.of(2026, 6, 1));
        assertThat(summary).anySatisfy(c -> {
            assertThat(c.companyId()).isEqualTo(companyId);
            assertThat(c.completeness()).isEqualTo(ReconciliationService.Completeness.PARTIAL);
        });
    }

    @Test
    void tenantBSeesNoTenantATransactions() throws Exception {
        UUID companyA = asTenantWithCompany(TENANT_A);
        documents.upload(companyA, LocalDate.of(2026, 6, 1), "extras.pdf", "application/pdf", pdf("RECONSTUB"));
        asTenantWithCompany(TENANT_B);
        assertThat(companyTxns(companyA)).isEmpty();
    }
}
