package ro.myfinance.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ro.myfinance.common.audit.AuditRecorder;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.company.application.CompanyDirectory;
import ro.myfinance.company.domain.Company;
import ro.myfinance.ingestion.adapter.persistence.ImportFileRepository;
import ro.myfinance.ingestion.adapter.persistence.SourceConnectionRepository;
import ro.myfinance.ingestion.application.CloudFolderConnector;
import ro.myfinance.ingestion.application.ConnectorRegistry;
import ro.myfinance.ingestion.application.IngestionService;
import ro.myfinance.ingestion.domain.ImportFile;
import ro.myfinance.ingestion.domain.SourceConnection;
import ro.myfinance.intake.application.DocumentService;
import ro.myfinance.intake.domain.Document;
import ro.myfinance.intake.domain.DocumentSource;
import ro.myfinance.intake.domain.DocumentType;

class IngestionServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID COMPANY = UUID.randomUUID();

    private final SourceConnectionRepository connections = mock(SourceConnectionRepository.class);
    private final ImportFileRepository ledger = mock(ImportFileRepository.class);
    private final CompanyDirectory companies = mock(CompanyDirectory.class);
    private final DocumentService documents = mock(DocumentService.class);
    private final ConnectorRegistry registry = mock(ConnectorRegistry.class);
    private final ro.myfinance.intake.application.DocumentClassifier classifier =
            mock(ro.myfinance.intake.application.DocumentClassifier.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);

    private final ro.myfinance.notifications.application.NotificationService notifications =
            mock(ro.myfinance.notifications.application.NotificationService.class);
    private final ro.myfinance.ingestion.application.ModuleSyncStatusService syncStatus =
            mock(ro.myfinance.ingestion.application.ModuleSyncStatusService.class);
    private final FakeConnector fake = new FakeConnector();
    // Inline executor: module-month syncs run synchronously in the test thread (deterministic).
    private final IngestionService service = new IngestionService(connections, ledger, companies, documents, registry, classifier, audit, notifications, syncStatus, Runnable::run);

    private SourceConnection conn() {
        SourceConnection c = new SourceConnection(TENANT, "FAKE", "Drive payroll", "root", "PAYROLL");
        when(connections.findById(c.getId())).thenReturn(Optional.of(c));
        return c;
    }

    private void bind() {
        TenantContext.set(new TenantContext.Identity(TENANT, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        when(registry.forProvider("FAKE")).thenReturn(fake);
        Company co = mock(Company.class);
        lenient().when(co.getId()).thenReturn(COMPANY);
        lenient().when(co.getCui()).thenReturn("49443957");
        lenient().when(co.getLegalName()).thenReturn("INNOVATECODE IT SRL");
        when(companies.findAll()).thenReturn(List.of(co));
    }

    @org.junit.jupiter.api.BeforeEach
    void allowSyncStart() {
        lenient().when(syncStatus.tryStart(any(), any(), any())).thenReturn(true);
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    void importsAResolvedPayrollFile() {
        SourceConnection c = conn();
        bind();
        fake.files = List.of(new CloudFolderConnector.RemoteFile("f1", "stat_salarii.pdf",
                "INNOVATECODE IT SRL/2026-05", "application/pdf", 200, "e1", Instant.now()));
        when(ledger.findByConnectionIdAndSourceRef(c.getId(), "f1")).thenReturn(Optional.empty());
        when(ledger.existsByConnectionIdAndCompanyIdAndPeriodMonthAndContentSha256AndStatus(eq(c.getId()), any(), any(), any(), any())).thenReturn(false);
        Document doc = mock(Document.class);
        when(doc.getId()).thenReturn(UUID.randomUUID());
        when(documents.upload(eq(COMPANY), eq(LocalDate.of(2026, 5, 1)), eq("stat_salarii.pdf"),
                eq("application/pdf"), any(), eq(DocumentType.PAYROLL), eq(DocumentSource.DRIVE))).thenReturn(doc);

        var r = service.sync(c.getId());

        assertThat(r.imported()).isEqualTo(1);
        verify(documents).upload(eq(COMPANY), eq(LocalDate.of(2026, 5, 1)), eq("stat_salarii.pdf"),
                eq("application/pdf"), any(), eq(DocumentType.PAYROLL), eq(DocumentSource.DRIVE));
    }

    @Test
    void skipsAlreadyImportedFile() {
        SourceConnection c = conn();
        bind();
        fake.files = List.of(new CloudFolderConnector.RemoteFile("f1", "stat.pdf",
                "INNOVATECODE IT SRL/2026-05", "application/pdf", 200, "e1", Instant.now()));
        ImportFile prior = new ImportFile(TENANT, c.getId(), "f1", "e1", "sha", "stat.pdf",
                "INNOVATECODE IT SRL/2026-05", UUID.randomUUID(), java.time.LocalDate.of(2026, 5, 1),
                UUID.randomUUID(), ImportFile.Status.IMPORTED, null);
        when(ledger.findByConnectionIdAndSourceRef(c.getId(), "f1")).thenReturn(Optional.of(prior));

        var r = service.sync(c.getId());

        assertThat(r.skipped()).isEqualTo(1);
        assertThat(r.imported()).isZero();
        verify(documents, never()).upload(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void queuesForReviewWhenCompanyCannotBeMatched() {
        SourceConnection c = conn();
        bind();
        fake.files = List.of(new CloudFolderConnector.RemoteFile("f9", "mystery.pdf",
                "Unknown Client SRL/2026-05", "application/pdf", 200, "e9", Instant.now()));
        when(ledger.findByConnectionIdAndSourceRef(c.getId(), "f9")).thenReturn(Optional.empty());

        var r = service.sync(c.getId());

        assertThat(r.needsReview()).isEqualTo(1);
        assertThat(r.imported()).isZero();
        verify(documents, never()).upload(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void driveEnabledForDetectsAPayrollDriveConnection() {
        SourceConnection drive = new SourceConnection(TENANT, "GOOGLE_DRIVE", "D", "root", "PAYROLL");
        when(connections.findByOrderByCreatedAtDesc()).thenReturn(List.of(drive));
        assertThat(service.driveEnabledFor("PAYROLL")).isTrue();
        assertThat(service.driveEnabledFor("INVOICE")).isFalse();
    }

    @Test
    void syncCompanyMonthImportsOnlyThatCompanyAndMonth() {
        UUID companyB = UUID.randomUUID();
        TenantContext.set(new TenantContext.Identity(TENANT, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        SourceConnection drive = new SourceConnection(TENANT, "GOOGLE_DRIVE", "D", "root", "PAYROLL");
        when(connections.findByOrderByCreatedAtDesc()).thenReturn(List.of(drive));
        when(registry.forProvider("GOOGLE_DRIVE")).thenReturn(fake);
        Company a = mock(Company.class);
        lenient().when(a.getId()).thenReturn(COMPANY);
        lenient().when(a.getCui()).thenReturn("49443957");
        lenient().when(a.getLegalName()).thenReturn("INNOVATECODE IT SRL");
        Company b = mock(Company.class);
        lenient().when(b.getId()).thenReturn(companyB);
        lenient().when(b.getCui()).thenReturn("39112764");
        lenient().when(b.getLegalName()).thenReturn("Lumina Verde SRL");
        when(companies.findAll()).thenReturn(List.of(a, b));
        fake.files = List.of(
                new CloudFolderConnector.RemoteFile("a", "Stat_salarii_2026_04.pdf", "INNOVATECODE IT SRL/2026/04 Aprilie", "application/pdf", 100, "ea", null),
                new CloudFolderConnector.RemoteFile("b", "Stat_salarii_2026_05.pdf", "INNOVATECODE IT SRL/2026/05 Mai", "application/pdf", 100, "eb", null),
                new CloudFolderConnector.RemoteFile("c", "Stat_salarii_2026_04.pdf", "Lumina Verde SRL/2026/04 Aprilie", "application/pdf", 100, "ec", null));
        when(ledger.findByConnectionIdAndSourceRef(eq(drive.getId()), any())).thenReturn(Optional.empty());
        when(ledger.existsByConnectionIdAndCompanyIdAndPeriodMonthAndContentSha256AndStatus(eq(drive.getId()), any(), any(), any(), any())).thenReturn(false);
        Document doc = mock(Document.class);
        when(doc.getId()).thenReturn(UUID.randomUUID());
        when(documents.upload(eq(COMPANY), eq(LocalDate.of(2026, 4, 1)), eq("Stat_salarii_2026_04.pdf"), any(), any(),
                eq(DocumentType.PAYROLL), eq(DocumentSource.DRIVE))).thenReturn(doc);

        var r = service.syncCompanyMonth("PAYROLL", COMPANY, LocalDate.of(2026, 4, 1));

        assertThat(r.imported()).isEqualTo(1); // only INNOVATECODE / April
        verify(documents).upload(eq(COMPANY), eq(LocalDate.of(2026, 4, 1)), eq("Stat_salarii_2026_04.pdf"), any(), any(),
                eq(DocumentType.PAYROLL), eq(DocumentSource.DRIVE));
        verify(documents, never()).upload(eq(companyB), any(), any(), any(), any(), any(), any()); // other company not touched
    }

    @Test
    void mixedSyncRoutesUntypedFilesThroughTheClassifier() {
        // A mixed "acte contabile" folder under a GENERAL Drive connection (no forced type): files not in
        // a type sub-folder must be uploaded with type=null so the classifier splits statements/invoices.
        TenantContext.set(new TenantContext.Identity(TENANT, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        SourceConnection drive = new SourceConnection(TENANT, "GOOGLE_DRIVE", "D", "root", null); // no forced type
        when(connections.findByOrderByCreatedAtDesc()).thenReturn(List.of(drive));
        when(registry.forProvider("GOOGLE_DRIVE")).thenReturn(fake);
        Company a = mock(Company.class);
        lenient().when(a.getId()).thenReturn(COMPANY);
        lenient().when(a.getCui()).thenReturn("49443957");
        lenient().when(a.getLegalName()).thenReturn("INNOVATECODE IT SRL");
        when(companies.findAll()).thenReturn(List.of(a));
        fake.files = List.of(new CloudFolderConnector.RemoteFile("m1", "extras.pdf",
                "INNOVATECODE IT SRL/2026/05 Mai/acte contabile", "application/pdf", 100, "e1", null));
        when(ledger.findByConnectionIdAndSourceRef(eq(drive.getId()), any())).thenReturn(Optional.empty());
        when(ledger.existsByConnectionIdAndCompanyIdAndPeriodMonthAndContentSha256AndStatus(eq(drive.getId()), any(), any(), any(), any())).thenReturn(false);
        Document doc = mock(Document.class);
        when(doc.getId()).thenReturn(UUID.randomUUID());
        when(documents.upload(eq(COMPANY), eq(LocalDate.of(2026, 5, 1)), eq("extras.pdf"), any(), any(),
                isNull(), eq(DocumentSource.DRIVE))).thenReturn(doc);

        var r = service.syncCompanyMonth("MIXED", COMPANY, LocalDate.of(2026, 5, 1));

        assertThat(r.imported()).isEqualTo(1);
        // type is null → DocumentService runs the content classifier (statement vs invoice).
        verify(documents).upload(eq(COMPANY), eq(LocalDate.of(2026, 5, 1)), eq("extras.pdf"), any(), any(),
                isNull(), eq(DocumentSource.DRIVE));
    }

    @Test
    void accountingDriveClassifiesByContentAndAcceptsOnlyBankAndInvoice() {
        // The ACCOUNTING (contabilitate clienti) drive: Company/year-month/mixed. Type is decided by CONTENT;
        // only bank statements & invoices are accepted, anything else goes to review.
        TenantContext.set(new TenantContext.Identity(TENANT, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        SourceConnection acct = new SourceConnection(TENANT, "GOOGLE_DRIVE", "Contabilitate", "root", null);
        acct.setPurpose("ACCOUNTING");
        when(connections.findByOrderByCreatedAtDesc()).thenReturn(List.of(acct));
        when(registry.forProvider("GOOGLE_DRIVE")).thenReturn(fake);
        Company a = mock(Company.class);
        lenient().when(a.getId()).thenReturn(COMPANY);
        lenient().when(a.getCui()).thenReturn("49443957");
        lenient().when(a.getLegalName()).thenReturn("ACME SRL");
        when(companies.findAll()).thenReturn(List.of(a));
        // Real client layout: Contabilitate <Company>/<year>/<N. LunăRo> — period resolved from year + month.
        fake.files = List.of(
                new CloudFolderConnector.RemoteFile("b", "extras.pdf", "Contabilitate ACME SRL/2026/3. Martie", "application/pdf", 100, "e1", null),
                new CloudFolderConnector.RemoteFile("i", "factura.pdf", "Contabilitate ACME SRL/2026/3. Martie", "application/pdf", 100, "e2", null),
                new CloudFolderConnector.RemoteFile("x", "random.pdf", "Contabilitate ACME SRL/2026/3. Martie", "application/pdf", 100, "e3", null));
        when(classifier.classify(any(), any(), any())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            if (name.startsWith("extras")) return DocumentType.BANK_STATEMENT;
            if (name.startsWith("factura")) return DocumentType.INVOICE;
            return DocumentType.UNCLASSIFIED;
        });
        when(ledger.findByConnectionIdAndSourceRef(eq(acct.getId()), any())).thenReturn(Optional.empty());
        when(ledger.existsByConnectionIdAndCompanyIdAndPeriodMonthAndContentSha256AndStatus(eq(acct.getId()), any(), any(), any(), any())).thenReturn(false);
        Document doc = mock(Document.class);
        when(doc.getId()).thenReturn(UUID.randomUUID());
        when(documents.upload(eq(COMPANY), eq(LocalDate.of(2026, 3, 1)), any(), any(), any(), any(), eq(DocumentSource.DRIVE))).thenReturn(doc);

        var r = service.syncCompanyMonth("MIXED", COMPANY, LocalDate.of(2026, 3, 1));

        assertThat(r.imported()).isEqualTo(2);    // extras (bank) + factura (invoice)
        assertThat(r.needsReview()).isEqualTo(1); // random → not a bank/invoice
        verify(documents).upload(eq(COMPANY), eq(LocalDate.of(2026, 3, 1)), eq("extras.pdf"), any(), any(),
                eq(DocumentType.BANK_STATEMENT), eq(DocumentSource.DRIVE));
        verify(documents).upload(eq(COMPANY), eq(LocalDate.of(2026, 3, 1)), eq("factura.pdf"), any(), any(),
                eq(DocumentType.INVOICE), eq(DocumentSource.DRIVE));
    }

    @Test
    void flagsWrongPeriodAndUnclassifiedFiles() {
        TenantContext.set(new TenantContext.Identity(TENANT, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        SourceConnection drive = new SourceConnection(TENANT, "GOOGLE_DRIVE", "D", "root", "PAYROLL");
        when(connections.findByOrderByCreatedAtDesc()).thenReturn(List.of(drive));
        when(registry.forProvider("GOOGLE_DRIVE")).thenReturn(fake);
        Company a = mock(Company.class);
        lenient().when(a.getId()).thenReturn(COMPANY);
        lenient().when(a.getCui()).thenReturn("49443957");
        lenient().when(a.getLegalName()).thenReturn("INNOVATECODE IT SRL");
        when(companies.findAll()).thenReturn(List.of(a));
        fake.files = List.of(
                new CloudFolderConnector.RemoteFile("ok", "Fluturas_2026_04.pdf", "INNOVATECODE IT SRL/2026/04 Aprilie", "application/pdf", 100, "e1", null),
                new CloudFolderConnector.RemoteFile("wp", "Pontaj_2026_05.pdf", "INNOVATECODE IT SRL/2026/04 Aprilie", "application/pdf", 100, "e2", null),
                new CloudFolderConnector.RemoteFile("uc", "some_invoice.pdf", "INNOVATECODE IT SRL/2026/04 Aprilie", "application/pdf", 100, "e3", null));
        when(ledger.findByConnectionIdAndSourceRef(eq(drive.getId()), any())).thenReturn(Optional.empty());
        when(ledger.existsByConnectionIdAndCompanyIdAndPeriodMonthAndContentSha256AndStatus(eq(drive.getId()), any(), any(), any(), any())).thenReturn(false);
        Document doc = mock(Document.class);
        lenient().when(doc.getId()).thenReturn(UUID.randomUUID());
        lenient().when(documents.upload(any(), any(), any(), any(), any(), any(), any())).thenReturn(doc);

        var r = service.syncCompanyMonth("PAYROLL", COMPANY, LocalDate.of(2026, 4, 1));

        assertThat(r.imported()).isEqualTo(1);     // Fluturas April
        assertThat(r.needsReview()).isEqualTo(2);  // Pontaj May (wrong period) + invoice (unclassified)
        assertThat(r.issues()).hasSize(2);
        assertThat(r.issues()).anyMatch(i -> i.reason().startsWith("Wrong period"));
        assertThat(r.issues()).anyMatch(i -> i.reason().startsWith("Unclassified"));
    }

    @Test
    void notifiesRepsWhenNewPreviousMonthPayrollArrives() {
        java.time.LocalDate prev = java.time.YearMonth.now(java.time.ZoneOffset.UTC).minusMonths(1).atDay(1);
        String mm = String.format("%02d", prev.getMonthValue());
        TenantContext.set(new TenantContext.Identity(TENANT, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        SourceConnection drive = new SourceConnection(TENANT, "GOOGLE_DRIVE", "D", "root", "PAYROLL");
        when(connections.findByOrderByCreatedAtDesc()).thenReturn(List.of(drive));
        when(registry.forProvider("GOOGLE_DRIVE")).thenReturn(fake);
        Company a = mock(Company.class);
        lenient().when(a.getId()).thenReturn(COMPANY);
        lenient().when(a.getCui()).thenReturn("49443957");
        lenient().when(a.getLegalName()).thenReturn("INNOVATECODE IT SRL");
        when(companies.findAll()).thenReturn(List.of(a));
        fake.files = List.of(new CloudFolderConnector.RemoteFile("p", "Stat_salarii_" + prev.getYear() + "_" + mm + ".pdf",
                "INNOVATECODE IT SRL/" + prev.getYear() + "/" + mm + " luna", "application/pdf", 100, "e1", null));
        when(ledger.findByConnectionIdAndSourceRef(eq(drive.getId()), any())).thenReturn(Optional.empty());
        when(ledger.existsByConnectionIdAndCompanyIdAndPeriodMonthAndContentSha256AndStatus(eq(drive.getId()), any(), any(), any(), any())).thenReturn(false);
        Document doc = mock(Document.class);
        when(doc.getId()).thenReturn(UUID.randomUUID());
        when(documents.upload(any(), any(), any(), any(), any(), any(), any())).thenReturn(doc);

        service.syncCompanyMonth("PAYROLL", COMPANY, prev);

        verify(notifications).notifyCompanyReps(eq(COMPANY), eq("PAYROLL_READY"), any(), any());
    }

    @Test
    void scopedSyncCrawlsOnlyTheCompanyFolderAndForcesTheCompany() {
        TenantContext.set(new TenantContext.Identity(TENANT, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        SourceConnection drive = new SourceConnection(TENANT, "GOOGLE_DRIVE", "D", "root", null);
        when(connections.findByOrderByCreatedAtDesc()).thenReturn(List.of(drive));
        when(registry.forProvider("GOOGLE_DRIVE")).thenReturn(fake);
        Company a = mock(Company.class);
        lenient().when(a.getId()).thenReturn(COMPANY);
        lenient().when(a.getCui()).thenReturn("49443957");
        lenient().when(a.getLegalName()).thenReturn("INNOVATECODE IT SRL");
        when(companies.findAll()).thenReturn(List.of(a));
        when(companies.findById(COMPANY)).thenReturn(Optional.of(a));
        // Root holds the company folder; crawling it yields a file whose PATH carries no company segment —
        // so the company can only come from the located folder (companyKnown), not path resolution.
        fake.folders = List.of(new CloudFolderConnector.Folder("cf1", "INNOVATECODE IT SRL"));
        fake.filesByFolder.put("cf1", List.of(new CloudFolderConnector.RemoteFile(
                "s1", "extras.pdf", "2026/05 Mai/acte contabile", "application/pdf", 100, "e1", null)));
        when(ledger.findByConnectionIdAndSourceRef(eq(drive.getId()), any())).thenReturn(Optional.empty());
        when(ledger.existsByConnectionIdAndCompanyIdAndPeriodMonthAndContentSha256AndStatus(
                eq(drive.getId()), any(), any(), any(), any())).thenReturn(false);
        Document doc = mock(Document.class);
        when(doc.getId()).thenReturn(UUID.randomUUID());
        when(documents.upload(eq(COMPANY), eq(LocalDate.of(2026, 5, 1)), eq("extras.pdf"),
                any(), any(), isNull(), eq(DocumentSource.DRIVE))).thenReturn(doc);

        var r = service.syncCompanyMonth("MIXED", COMPANY, LocalDate.of(2026, 5, 1));

        assertThat(r.imported()).isEqualTo(1);
        // Only the company folder was crawled — the whole-drive list() was never used.
        verify(documents).upload(eq(COMPANY), eq(LocalDate.of(2026, 5, 1)), eq("extras.pdf"),
                any(), any(), isNull(), eq(DocumentSource.DRIVE));
    }

    @Test
    void monthFirstLayoutImportsDeclarationByCuiAndDropsNoiseReceiptsAndUnsignedTwins() {
        TenantContext.set(new TenantContext.Identity(TENANT, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        SourceConnection c = new SourceConnection(TENANT, "FAKE", "Firm drive", "root", null); // general, mixed
        c.setConfig("{\"layout\":\"month_first\"}");
        when(connections.findById(c.getId())).thenReturn(Optional.of(c));
        when(registry.forProvider("FAKE")).thenReturn(fake);
        Company co = mock(Company.class);
        lenient().when(co.getId()).thenReturn(COMPANY);
        lenient().when(co.getCui()).thenReturn("44570402");
        lenient().when(co.getLegalName()).thenReturn("MORARU TECH SRL");
        when(companies.findAll()).thenReturn(List.of(co));
        fake.files = List.of(
                // signed declaration → imported (company by CUI-in-filename, type by folder, period by filename)
                new CloudFolderConnector.RemoteFile("f1",
                        "D700_44570402_2026_07 - trecere impozit profit_semnat.pdf", "7. Iulie 2026/D700",
                        "application/pdf", 100, "e1", null),
                // unsigned twin of the same declaration → skipped (superseded by the signed copy)
                new CloudFolderConnector.RemoteFile("f2",
                        "D700_44570402_2026_07 - trecere impozit profit.pdf", "7. Iulie 2026/D700",
                        "application/pdf", 100, "e2", null),
                // ANAF submission receipt → skipped
                new CloudFolderConnector.RemoteFile("f3",
                        "D700_44570402_2026_07_recipisa_118.pdf", "7. Iulie 2026/D700",
                        "application/pdf", 100, "e3", null),
                // valid declaration name but in a non-month top folder → out of scope, skipped
                new CloudFolderConnector.RemoteFile("f4",
                        "D700_44570402_2026_07 - x.pdf", "D060", "application/pdf", 100, "e4", null));
        when(ledger.findByConnectionIdAndSourceRef(eq(c.getId()), any())).thenReturn(Optional.empty());
        when(ledger.existsByConnectionIdAndCompanyIdAndPeriodMonthAndContentSha256AndStatus(
                eq(c.getId()), any(), any(), any(), any())).thenReturn(false);
        Document doc = mock(Document.class);
        when(doc.getId()).thenReturn(UUID.randomUUID());
        when(documents.upload(eq(COMPANY), eq(LocalDate.of(2026, 7, 1)),
                eq("D700_44570402_2026_07 - trecere impozit profit_semnat.pdf"), any(), any(),
                eq(DocumentType.DECLARATION), eq(DocumentSource.DRIVE))).thenReturn(doc);

        var r = service.sync(c.getId());

        assertThat(r.imported()).isEqualTo(1);
        verify(documents, times(1)).upload(any(), any(), any(), any(), any(), any(), any());
        verify(documents).upload(eq(COMPANY), eq(LocalDate.of(2026, 7, 1)),
                eq("D700_44570402_2026_07 - trecere impozit profit_semnat.pdf"), any(), any(),
                eq(DocumentType.DECLARATION), eq(DocumentSource.DRIVE));
    }

    @Test
    void monthFirstLayoutImportsInterimBalanceByCompanyFolderAndTrimester() {
        TenantContext.set(new TenantContext.Identity(TENANT, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        SourceConnection c = new SourceConnection(TENANT, "FAKE", "Firm drive", "root", null);
        c.setConfig("{\"layout\":\"month_first\"}");
        when(connections.findById(c.getId())).thenReturn(Optional.of(c));
        when(registry.forProvider("FAKE")).thenReturn(fake);
        Company co = mock(Company.class);
        lenient().when(co.getId()).thenReturn(COMPANY);
        lenient().when(co.getCui()).thenReturn("44570402");
        lenient().when(co.getLegalName()).thenReturn("STONEAGE INDUSTRY SRL");
        when(companies.findAll()).thenReturn(List.of(co));
        // Real layout: the trial balance is a plain FILE directly in the company folder (no subfolder),
        // alongside the full statement and AGA docs — only "balanta de verificare …" must be imported.
        fake.files = List.of(
                new CloudFolderConnector.RemoteFile("b1", "balanta_de_verificare iunie 2026.pdf",
                        "Bilant interimar T2 an 2026/STONEAGE INDUSTRY SRL", "application/pdf", 100, "e1", null),
                new CloudFolderConnector.RemoteFile("b2", "Bilant interimar iunie 2026.pdf",
                        "Bilant interimar T2 an 2026/STONEAGE INDUSTRY SRL", "application/pdf", 100, "e2", null),
                new CloudFolderConnector.RemoteFile("b3", "Hotararea AGA print pdf.pdf",
                        "Bilant interimar T2 an 2026/STONEAGE INDUSTRY SRL", "application/pdf", 100, "e3", null));
        when(ledger.findByConnectionIdAndSourceRef(eq(c.getId()), any())).thenReturn(Optional.empty());
        when(ledger.existsByConnectionIdAndCompanyIdAndPeriodMonthAndContentSha256AndStatus(
                eq(c.getId()), any(), any(), any(), any())).thenReturn(false);
        Document doc = mock(Document.class);
        when(doc.getId()).thenReturn(UUID.randomUUID());
        when(documents.upload(eq(COMPANY), eq(LocalDate.of(2026, 6, 1)), eq("balanta_de_verificare iunie 2026.pdf"),
                any(), any(), eq(DocumentType.TRIAL_BALANCE), eq(DocumentSource.DRIVE))).thenReturn(doc);

        var r = service.sync(c.getId());

        assertThat(r.imported()).isEqualTo(1); // only the balanta de verificare, not the statement/AGA docs
        verify(documents, times(1)).upload(any(), any(), any(), any(), any(), any(), any());
        verify(documents).upload(eq(COMPANY), eq(LocalDate.of(2026, 6, 1)), eq("balanta_de_verificare iunie 2026.pdf"),
                any(), any(), eq(DocumentType.TRIAL_BALANCE), eq(DocumentSource.DRIVE));
    }

    /** In-memory connector — feeds the pipeline a controlled file list. */
    @Test
    void syncModuleMonthImportsOnlyThatModuleAcrossAllCompanies() {
        TenantContext.set(new TenantContext.Identity(TENANT, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        SourceConnection drive = new SourceConnection(TENANT, "GOOGLE_DRIVE", "Firm", "root", null);
        drive.setConfig("{\"layout\":\"month_first\"}");
        when(connections.findByOrderByCreatedAtDesc()).thenReturn(List.of(drive));
        when(registry.forProvider("GOOGLE_DRIVE")).thenReturn(fake);
        Company a = mock(Company.class);
        lenient().when(a.getId()).thenReturn(COMPANY);
        lenient().when(a.getCui()).thenReturn("49443957");
        lenient().when(a.getLegalName()).thenReturn("INNOVATECODE IT SRL");
        when(companies.findAll()).thenReturn(List.of(a));
        // Root subfolders: the month folder is located by name for the requested month.
        fake.folders = List.of(new CloudFolderConnector.Folder("month7", "7. Iulie 2026"),
                new CloudFolderConnector.Folder("bilantT2", "Bilant interimar T2 an 2026"));
        // The month folder holds BOTH payroll (State de plata) and a declaration (D 112) for July.
        fake.filesByFolder.put("month7", List.of(
                new CloudFolderConnector.RemoteFile("p", "fluturas#_INNOVATECODE IT SRL c.f. 49443957_2026_07.pdf",
                        "State de plata", "application/pdf", 100, "e1", null),
                new CloudFolderConnector.RemoteFile("d", "D112_49443957_2026_07.pdf",
                        "D 112", "application/pdf", 100, "e2", null)));
        when(ledger.findByConnectionIdAndSourceRef(eq(drive.getId()), any())).thenReturn(Optional.empty());
        when(ledger.existsByConnectionIdAndCompanyIdAndPeriodMonthAndContentSha256AndStatus(
                eq(drive.getId()), any(), any(), any(), any())).thenReturn(false);
        Document doc = mock(Document.class);
        when(doc.getId()).thenReturn(UUID.randomUUID());
        when(documents.upload(eq(COMPANY), eq(LocalDate.of(2026, 7, 1)),
                eq("fluturas#_INNOVATECODE IT SRL c.f. 49443957_2026_07.pdf"), any(), any(),
                eq(DocumentType.PAYROLL), eq(DocumentSource.DRIVE))).thenReturn(doc);

        service.syncModuleMonth("PAYROLL", LocalDate.of(2026, 7, 1));

        // only the payroll imported, not the D112 declaration in the same folder (async runner finished inline)
        verify(syncStatus).markFinish(eq("PAYROLL"), any(), org.mockito.ArgumentMatchers.contains("1 imported"));
        verify(documents, times(1)).upload(any(), any(), any(), any(), any(), any(), any());
        verify(documents).upload(eq(COMPANY), eq(LocalDate.of(2026, 7, 1)),
                eq("fluturas#_INNOVATECODE IT SRL c.f. 49443957_2026_07.pdf"), any(), any(),
                eq(DocumentType.PAYROLL), eq(DocumentSource.DRIVE));
        verify(documents, never()).upload(any(), any(), any(), any(), any(), eq(DocumentType.DECLARATION), any());
    }

    @Test
    void syncModuleMonthDedupsTheRenamedDeclarationCopy() {
        TenantContext.set(new TenantContext.Identity(TENANT, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        SourceConnection drive = new SourceConnection(TENANT, "GOOGLE_DRIVE", "Firm", "root", null);
        drive.setConfig("{\"layout\":\"month_first\"}");
        when(connections.findByOrderByCreatedAtDesc()).thenReturn(List.of(drive));
        when(registry.forProvider("GOOGLE_DRIVE")).thenReturn(fake);
        Company a = mock(Company.class);
        lenient().when(a.getId()).thenReturn(COMPANY);
        lenient().when(a.getCui()).thenReturn("49443957");
        lenient().when(a.getLegalName()).thenReturn("INNOVATECODE IT SRL");
        when(companies.findAll()).thenReturn(List.of(a));
        fake.folders = List.of(new CloudFolderConnector.Folder("month6", "6. Iunie 2026"));
        // The SAME D112 declaration under both filenames the firm keeps, in the same D112 folder.
        fake.filesByFolder.put("month6", List.of(
                new CloudFolderConnector.RemoteFile("f1", "D112_49443957_2026_06.pdf", "D112",
                        "application/pdf", 100, "e1", null),
                new CloudFolderConnector.RemoteFile("f2", "INNOVATECODE IT SRL_D112_062026_49443957.PDF", "D112",
                        "application/pdf", 100, "e2", null)));
        when(ledger.findByConnectionIdAndSourceRef(eq(drive.getId()), any())).thenReturn(Optional.empty());
        when(ledger.existsByConnectionIdAndCompanyIdAndPeriodMonthAndContentSha256AndStatus(
                eq(drive.getId()), any(), any(), any(), any())).thenReturn(false);
        Document doc = mock(Document.class);
        when(doc.getId()).thenReturn(UUID.randomUUID());
        when(documents.upload(eq(COMPANY), eq(LocalDate.of(2026, 6, 1)), eq("D112_49443957_2026_06.pdf"),
                any(), any(), eq(DocumentType.DECLARATION), eq(DocumentSource.DRIVE))).thenReturn(doc);

        service.syncModuleMonth("DECLARATION", LocalDate.of(2026, 6, 1));

        // only the ANAF original imported; the renamed copy is dropped
        verify(syncStatus).markFinish(eq("DECLARATION"), any(), org.mockito.ArgumentMatchers.contains("1 imported"));
        verify(documents, times(1)).upload(any(), any(), any(), any(), any(), any(), any());
        verify(documents).upload(eq(COMPANY), eq(LocalDate.of(2026, 6, 1)), eq("D112_49443957_2026_06.pdf"),
                any(), any(), eq(DocumentType.DECLARATION), eq(DocumentSource.DRIVE));
        verify(documents, never()).upload(any(), any(), eq("INNOVATECODE IT SRL_D112_062026_49443957.PDF"),
                any(), any(), any(), any());
    }

    @Test
    void syncCompanyMonthRejectedWhileAMonthWideSyncRuns() {
        TenantContext.set(new TenantContext.Identity(TENANT, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        SourceConnection drive = new SourceConnection(TENANT, "GOOGLE_DRIVE", "D", "root", "PAYROLL");
        when(connections.findByOrderByCreatedAtDesc()).thenReturn(List.of(drive));
        when(syncStatus.isRunning(eq("PAYROLL"), any())).thenReturn(true); // a month-wide sync holds the slot

        assertThatThrownBy(() -> service.syncCompanyMonth("PAYROLL", COMPANY, LocalDate.of(2026, 7, 1)))
                .isInstanceOf(ro.myfinance.common.web.ConflictException.class);
        verify(documents, never()).upload(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void reImportOfAnUpdatedFileReplacesThePreviousDocument() {
        SourceConnection c = conn();
        bind();
        fake.files = List.of(new CloudFolderConnector.RemoteFile("f1", "stat_salarii.pdf",
                "INNOVATECODE IT SRL/2026-05", "application/pdf", 200, "NEW-etag", Instant.now()));
        UUID oldDoc = UUID.randomUUID();
        ImportFile prior = new ImportFile(TENANT, c.getId(), "f1", "OLD-etag", "oldsha", "stat_salarii.pdf",
                "INNOVATECODE IT SRL/2026-05", COMPANY, LocalDate.of(2026, 5, 1), oldDoc, ImportFile.Status.IMPORTED, null);
        when(ledger.findByConnectionIdAndSourceRef(c.getId(), "f1")).thenReturn(Optional.of(prior));
        when(ledger.existsByConnectionIdAndCompanyIdAndPeriodMonthAndContentSha256AndStatus(
                eq(c.getId()), any(), any(), any(), any())).thenReturn(false);
        Document newDoc = mock(Document.class);
        when(newDoc.getId()).thenReturn(UUID.randomUUID());
        when(documents.upload(eq(COMPANY), eq(LocalDate.of(2026, 5, 1)), eq("stat_salarii.pdf"), any(), any(),
                eq(DocumentType.PAYROLL), eq(DocumentSource.DRIVE))).thenReturn(newDoc);

        var r = service.sync(c.getId());

        assertThat(r.imported()).isEqualTo(1);
        verify(documents).delete(oldDoc); // the superseded old version is removed on re-import
    }

    @Test
    void syncModuleMonthPrefersTheFinalPayrollOverTheInitial() {
        UUID evtimia = UUID.randomUUID();
        TenantContext.set(new TenantContext.Identity(TENANT, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        SourceConnection drive = new SourceConnection(TENANT, "GOOGLE_DRIVE", "Firm", "root", null);
        drive.setConfig("{\"layout\":\"month_first\"}");
        when(connections.findByOrderByCreatedAtDesc()).thenReturn(List.of(drive));
        when(registry.forProvider("GOOGLE_DRIVE")).thenReturn(fake);
        Company a = mock(Company.class);
        lenient().when(a.getId()).thenReturn(evtimia);
        lenient().when(a.getCui()).thenReturn("12345678");
        lenient().when(a.getLegalName()).thenReturn("EVTIMIA SRL");
        when(companies.findAll()).thenReturn(List.of(a));
        fake.folders = List.of(new CloudFolderConnector.Folder("month4", "4. Aprilie 2026"));
        fake.filesByFolder.put("month4", List.of(
                new CloudFolderConnector.RemoteFile("i", "Fluturasi_EVTIMIA SRL_2026_04_Initial.pdf",
                        "State de plata", "application/pdf", 100, "e1", null),
                new CloudFolderConnector.RemoteFile("f", "Fluturasi_EVTIMIA SRL_2026_04_final.pdf",
                        "State de plata", "application/pdf", 100, "e2", null)));
        when(ledger.findByConnectionIdAndSourceRef(eq(drive.getId()), any())).thenReturn(Optional.empty());
        when(ledger.existsByConnectionIdAndCompanyIdAndPeriodMonthAndContentSha256AndStatus(
                eq(drive.getId()), any(), any(), any(), any())).thenReturn(false);
        Document doc = mock(Document.class);
        when(doc.getId()).thenReturn(UUID.randomUUID());
        when(documents.upload(eq(evtimia), eq(LocalDate.of(2026, 4, 1)), eq("Fluturasi_EVTIMIA SRL_2026_04_final.pdf"),
                any(), any(), eq(DocumentType.PAYROLL), eq(DocumentSource.DRIVE))).thenReturn(doc);

        service.syncModuleMonth("PAYROLL", LocalDate.of(2026, 4, 1));

        // only the final imported, the Initial is dropped
        verify(syncStatus).markFinish(eq("PAYROLL"), any(), org.mockito.ArgumentMatchers.contains("1 imported"));
        verify(documents, times(1)).upload(any(), any(), any(), any(), any(), any(), any());
        verify(documents).upload(eq(evtimia), eq(LocalDate.of(2026, 4, 1)), eq("Fluturasi_EVTIMIA SRL_2026_04_final.pdf"),
                any(), any(), eq(DocumentType.PAYROLL), eq(DocumentSource.DRIVE));
        verify(documents, never()).upload(any(), any(), eq("Fluturasi_EVTIMIA SRL_2026_04_Initial.pdf"),
                any(), any(), any(), any());
    }

    static class FakeConnector implements CloudFolderConnector {
        List<RemoteFile> files = List.of();
        List<Folder> folders = List.of();                                   // root subfolders (scoped crawl)
        final java.util.Map<String, List<RemoteFile>> filesByFolder = new java.util.HashMap<>();
        @Override public String provider() { return "FAKE"; }
        @Override public Listing list(SourceConnection c, String cursor) { return new Listing(files, null); }
        @Override public Listing list(SourceConnection c, String startFolderId, String cursor) {
            return new Listing(filesByFolder.getOrDefault(startFolderId, files), null);
        }
        @Override public List<Folder> subfolders(SourceConnection c, String parentId) { return folders; }
        @Override public byte[] download(SourceConnection c, RemoteFile f) { return new byte[]{1, 2, 3}; }
    }
}
