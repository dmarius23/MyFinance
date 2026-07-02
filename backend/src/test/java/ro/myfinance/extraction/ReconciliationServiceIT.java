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
    @Autowired ro.myfinance.extraction.adapter.persistence.InvoiceRepository invoiceRepo;
    @Autowired ro.myfinance.extraction.adapter.persistence.TransactionInvoiceMatchRepository matchRepo;

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
        return companies.create("Client SRL", "RO-REC-" + UUID.randomUUID(), "SRL", "Cluj", null, null, null, null, null).getId();
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

    private UUID seedInvoice(UUID companyId, String iban, String amount, LocalDate date) {
        return seedInvoice(companyId, "ACME", iban, amount, date);
    }

    private UUID seedInvoice(UUID companyId, String supplierName, String iban, String amount, LocalDate date) {
        UUID tenantId = TENANT_A; // bound tenant in the active test
        // a document row is required (FK). Insert a minimal INVOICE document via jdbc, then an invoice row.
        UUID docId = UUID.randomUUID();
        jdbc.update("insert into document(id, tenant_id, company_id, period_month, type, source, status, "
                + "original_filename, content_type, size_bytes, storage_key) "
                + "values (?,?,?,?, 'INVOICE','EMPLOYEE','UPLOADED','inv.pdf','application/pdf',1,'k/"+docId+"')",
                docId, tenantId, companyId, java.sql.Date.valueOf(LocalDate.of(2026, 6, 1)));
        ro.myfinance.extraction.domain.Invoice inv = invoiceRepo.save(new ro.myfinance.extraction.domain.Invoice(
                tenantId, docId, companyId, LocalDate.of(2026, 6, 1), supplierName, iban,
                new java.math.BigDecimal(amount), date, "inv.pdf", "EXTRACTED"));
        return inv.getId();
    }

    @Test
    void autoMatchesByAmountAndNameWhenInvoiceHasNoIban() throws Exception {
        UUID companyId = asTenantWithCompany(TENANT_A);
        documents.upload(companyId, LocalDate.of(2026, 6, 1), "extras.pdf", "application/pdf", pdf("RECONSTUB"));
        // No supplier IBAN (e.g. a POS/card purchase), but the supplier name matches the SELGROS debit
        // (-200.00). Tier-2 matching (exact amount + distinctive name token) should link them.
        UUID invId = seedInvoice(companyId, "SELGROS Cash & Carry SRL", null, "200.00", LocalDate.of(2026, 6, 1));

        reconciliation.matchPeriod(companyId, LocalDate.of(2026, 6, 1));

        assertThat(matchRepo.findByInvoiceIdIn(List.of(invId))).hasSize(1);
    }

    @Test
    void doesNotAmountMatchWhenNameDiffers() throws Exception {
        UUID companyId = asTenantWithCompany(TENANT_A);
        documents.upload(companyId, LocalDate.of(2026, 6, 1), "extras.pdf", "application/pdf", pdf("RECONSTUB"));
        // Same -200.00 amount as the SELGROS debit, but an unrelated supplier and no IBAN → must NOT
        // auto-match (precision: an amount coincidence alone is not a match).
        UUID invId = seedInvoice(companyId, "TOTALLY DIFFERENT VENDOR SRL", null, "200.00", LocalDate.of(2026, 6, 1));

        reconciliation.matchPeriod(companyId, LocalDate.of(2026, 6, 1));

        assertThat(matchRepo.findByInvoiceIdIn(List.of(invId))).isEmpty();
    }

    @Test
    void autoMatchesInvoiceToSupplierTransaction() throws Exception {
        UUID companyId = asTenantWithCompany(TENANT_A);
        documents.upload(companyId, LocalDate.of(2026, 6, 1), "extras.pdf", "application/pdf", pdf("RECONSTUB"));
        BankTransaction supplier = companyTxns(companyId).stream()
                .filter(t -> "SELGROS".equals(t.getPartnerName())).findFirst().orElseThrow();
        // SELGROS supplier txn from the stub: partnerIban RO21SUPP, amount -200.00, date 2026-06-03
        seedInvoice(companyId, "RO21SUPP", "200.00", LocalDate.of(2026, 6, 1));
        reconciliation.matchPeriod(companyId, LocalDate.of(2026, 6, 1));

        assertThat(matchRepo.findByTransactionIdIn(List.of(supplier.getId()))).hasSize(1);
        var summary = reconciliation.completenessSummary(LocalDate.of(2026, 6, 1));
        assertThat(summary).anySatisfy(c -> {
            assertThat(c.companyId()).isEqualTo(companyId);
            assertThat(c.completeness()).isEqualTo(ReconciliationService.Completeness.COMPLETE);
        });
    }

    @Test
    void doesNotMatchInvoiceDatedAfterTransaction() throws Exception {
        UUID companyId = asTenantWithCompany(TENANT_A);
        documents.upload(companyId, LocalDate.of(2026, 6, 1), "extras.pdf", "application/pdf", pdf("RECONSTUB"));
        BankTransaction supplier = companyTxns(companyId).stream()
                .filter(t -> "SELGROS".equals(t.getPartnerName())).findFirst().orElseThrow();
        seedInvoice(companyId, "RO21SUPP", "200.00", LocalDate.of(2026, 6, 30)); // after the txn (06-03)
        reconciliation.matchPeriod(companyId, LocalDate.of(2026, 6, 1));
        assertThat(matchRepo.findByTransactionIdIn(List.of(supplier.getId()))).isEmpty();
    }

    @Test
    void manualLinkRejectsTxnBeforeInvoice() throws Exception {
        UUID companyId = asTenantWithCompany(TENANT_A);
        documents.upload(companyId, LocalDate.of(2026, 6, 1), "extras.pdf", "application/pdf", pdf("RECONSTUB"));
        BankTransaction supplier = companyTxns(companyId).stream()
                .filter(t -> "SELGROS".equals(t.getPartnerName())).findFirst().orElseThrow();
        UUID invId = seedInvoice(companyId, "RO21SUPP", "999.00", LocalDate.of(2026, 6, 30));
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                reconciliation.link(companyId, supplier.getId(), invId, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void manualLinkAndUnlink() throws Exception {
        UUID companyId = asTenantWithCompany(TENANT_A);
        documents.upload(companyId, LocalDate.of(2026, 6, 1), "extras.pdf", "application/pdf", pdf("RECONSTUB"));
        BankTransaction supplier = companyTxns(companyId).stream()
                .filter(t -> "SELGROS".equals(t.getPartnerName())).findFirst().orElseThrow();
        UUID invId = seedInvoice(companyId, "RO21SUPP", "200.00", LocalDate.of(2026, 6, 1));
        reconciliation.link(companyId, supplier.getId(), invId, null);
        assertThat(matchRepo.findByTransactionIdIn(List.of(supplier.getId()))).hasSize(1);
        reconciliation.unlink(companyId, supplier.getId(), invId);
        assertThat(matchRepo.findByTransactionIdIn(List.of(supplier.getId()))).isEmpty();
    }

    @Test
    void unlinkRejectsWrongCompany() throws Exception {
        UUID companyA = asTenantWithCompany(TENANT_A);
        UUID companyB = companies.create("Other SRL", "RO-OTH-" + UUID.randomUUID(), "SRL", "Cluj", null, null, null, null, null).getId();
        documents.upload(companyA, LocalDate.of(2026, 6, 1), "extras.pdf", "application/pdf", pdf("RECONSTUB"));
        BankTransaction supplier = companyTxns(companyA).stream()
                .filter(t -> "SELGROS".equals(t.getPartnerName())).findFirst().orElseThrow();
        UUID invId = seedInvoice(companyA, "RO21SUPP", "200.00", LocalDate.of(2026, 6, 1));
        reconciliation.link(companyA, supplier.getId(), invId, null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                reconciliation.unlink(companyB, supplier.getId(), invId))
                .isInstanceOf(ro.myfinance.common.web.NotFoundException.class);
        assertThat(matchRepo.findByTransactionIdIn(List.of(supplier.getId()))).hasSize(1); // link untouched
    }

    @Test
    void matchPeriodIsIdempotent() throws Exception {
        UUID companyId = asTenantWithCompany(TENANT_A);
        documents.upload(companyId, LocalDate.of(2026, 6, 1), "extras.pdf", "application/pdf", pdf("RECONSTUB"));
        BankTransaction supplier = companyTxns(companyId).stream()
                .filter(t -> "SELGROS".equals(t.getPartnerName())).findFirst().orElseThrow();
        seedInvoice(companyId, "RO21SUPP", "200.00", LocalDate.of(2026, 6, 1));
        reconciliation.matchPeriod(companyId, LocalDate.of(2026, 6, 1));
        reconciliation.matchPeriod(companyId, LocalDate.of(2026, 6, 1)); // re-run must not duplicate
        assertThat(matchRepo.findByTransactionIdIn(List.of(supplier.getId()))).hasSize(1);
    }

    @Test
    void manualLinkSupportsManyInvoicesPerTransaction() throws Exception {
        UUID companyId = asTenantWithCompany(TENANT_A);
        documents.upload(companyId, LocalDate.of(2026, 6, 1), "extras.pdf", "application/pdf", pdf("RECONSTUB"));
        BankTransaction supplier = companyTxns(companyId).stream()
                .filter(t -> "SELGROS".equals(t.getPartnerName())).findFirst().orElseThrow();
        UUID inv1 = seedInvoice(companyId, "RO21SUPP", "120.00", LocalDate.of(2026, 6, 1));
        UUID inv2 = seedInvoice(companyId, "RO21SUPP", "80.00", LocalDate.of(2026, 6, 2));
        reconciliation.link(companyId, supplier.getId(), inv1, null);
        reconciliation.link(companyId, supplier.getId(), inv2, null);
        var links = matchRepo.findByTransactionIdIn(List.of(supplier.getId()));
        assertThat(links).hasSize(2); // m:n — one payment settles two invoices
        // Default allocation fills each invoice to its total (120 + 80 = 200, the payment amount).
        assertThat(links.stream().map(m -> m.getAllocatedAmount().stripTrailingZeros())
                .collect(java.util.stream.Collectors.toSet()))
                .containsExactlyInAnyOrder(new java.math.BigDecimal("120"), new java.math.BigDecimal("80"));
    }

    @Test
    void partialPaymentAllocatesAndCapsAtRemaining() throws Exception {
        UUID companyId = asTenantWithCompany(TENANT_A);
        documents.upload(companyId, LocalDate.of(2026, 6, 1), "extras.pdf", "application/pdf", pdf("RECONSTUB"));
        BankTransaction supplier = companyTxns(companyId).stream()
                .filter(t -> "SELGROS".equals(t.getPartnerName())).findFirst().orElseThrow();
        // Invoice larger than the payment → partial allocation = the whole payment (200).
        UUID invId = seedInvoice(companyId, "RO21SUPP", "500.00", LocalDate.of(2026, 6, 1));
        reconciliation.link(companyId, supplier.getId(), invId, null);
        assertThat(matchRepo.findByTransactionIdIn(List.of(supplier.getId())).get(0)
                .getAllocatedAmount().stripTrailingZeros()).isEqualByComparingTo(new java.math.BigDecimal("200"));
        // Payment is now fully allocated → linking it to another invoice is rejected.
        UUID inv2 = seedInvoice(companyId, "RO21SUPP", "50.00", LocalDate.of(2026, 6, 1));
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                reconciliation.link(companyId, supplier.getId(), inv2, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
