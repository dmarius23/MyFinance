package ro.myfinance.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import ro.myfinance.company.adapter.persistence.CompanyRepository;
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
    private final CompanyRepository companies = mock(CompanyRepository.class);
    private final DocumentService documents = mock(DocumentService.class);
    private final ConnectorRegistry registry = mock(ConnectorRegistry.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);

    private final FakeConnector fake = new FakeConnector();
    private final IngestionService service = new IngestionService(connections, ledger, companies, documents, registry, audit);

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

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    void importsAResolvedPayrollFile() {
        SourceConnection c = conn();
        bind();
        fake.files = List.of(new CloudFolderConnector.RemoteFile("f1", "stat_salarii.pdf",
                "INNOVATECODE IT SRL/2026-05", "application/pdf", 200, "e1", Instant.now()));
        when(ledger.findByConnectionIdAndSourceRef(c.getId(), "f1")).thenReturn(Optional.empty());
        when(ledger.existsByConnectionIdAndContentSha256(eq(c.getId()), any())).thenReturn(false);
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
                "INNOVATECODE IT SRL/2026-05", UUID.randomUUID(), ImportFile.Status.IMPORTED, null);
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
        when(ledger.existsByConnectionIdAndContentSha256(eq(drive.getId()), any())).thenReturn(false);
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
        when(ledger.existsByConnectionIdAndContentSha256(eq(drive.getId()), any())).thenReturn(false);
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

    /** In-memory connector — feeds the pipeline a controlled file list. */
    static class FakeConnector implements CloudFolderConnector {
        List<RemoteFile> files = List.of();
        @Override public String provider() { return "FAKE"; }
        @Override public Listing list(SourceConnection c, String cursor) { return new Listing(files, null); }
        @Override public byte[] download(SourceConnection c, RemoteFile f) { return new byte[]{1, 2, 3}; }
    }
}
