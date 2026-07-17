package ro.myfinance.ingestion.application;

import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.audit.AuditRecorder;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.company.adapter.persistence.CompanyRepository;
import ro.myfinance.company.domain.Company;
import ro.myfinance.ingestion.adapter.persistence.ImportFileRepository;
import ro.myfinance.ingestion.adapter.persistence.SourceConnectionRepository;
import ro.myfinance.ingestion.application.CloudFolderConnector.Listing;
import ro.myfinance.ingestion.application.CloudFolderConnector.RemoteFile;
import ro.myfinance.ingestion.domain.ImportFile;
import ro.myfinance.ingestion.domain.SourceConnection;
import ro.myfinance.intake.application.DocumentService;
import ro.myfinance.intake.domain.DocumentSource;
import ro.myfinance.intake.domain.DocumentType;

/**
 * MOD-15 — pulls files from a configured cloud folder into the existing intake pipeline. For each new
 * file it resolves the client company and period from the folder layout, dedupes against the import
 * ledger, and (when resolved) calls {@link DocumentService#upload} exactly as a manual upload would —
 * so classification, extraction and reconciliation run unchanged. Unresolved files go to a review queue.
 */
@Service
@Transactional
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private static final long MAX_BYTES = 25L * 1024 * 1024;

    private final SourceConnectionRepository connections;
    private final ImportFileRepository ledger;
    private final CompanyRepository companies;
    private final DocumentService documents;
    private final ConnectorRegistry registry;
    private final AuditRecorder audit;
    private final ro.myfinance.notifications.application.NotificationService notifications;

    public IngestionService(SourceConnectionRepository connections, ImportFileRepository ledger,
                            CompanyRepository companies, DocumentService documents,
                            ConnectorRegistry registry, AuditRecorder audit,
                            ro.myfinance.notifications.application.NotificationService notifications) {
        this.connections = connections;
        this.ledger = ledger;
        this.companies = companies;
        this.documents = documents;
        this.registry = registry;
        this.audit = audit;
        this.notifications = notifications;
    }

    /** The previous calendar month (first of month) — the month reps are notified about. */
    private static LocalDate previousMonth() {
        return java.time.YearMonth.now(java.time.ZoneOffset.UTC).minusMonths(1).atDay(1);
    }

    // ---- connection management (TENANT_ADMIN) ----------------------------------------------------

    @Transactional(readOnly = true)
    public List<SourceConnection> list() {
        return connections.findByOrderByCreatedAtDesc();
    }

    public SourceConnection create(String provider, String displayName, String rootFolderId,
                                   String forcedType, boolean writeEnabled, String config) {
        UUID tenantId = TenantContext.tenantId().orElseThrow();
        registry.forProvider(provider); // fail fast if the provider has no connector
        SourceConnection c = new SourceConnection(tenantId, provider.toUpperCase(), displayName, rootFolderId, forcedType);
        c.setWriteEnabled(writeEnabled);
        if (config != null) {
            c.setConfig(config);
        }
        SourceConnection saved = connections.save(c);
        audit.record("SOURCE_CONNECTION_CREATED", "source_connection", saved.getId());
        return saved;
    }

    public SourceConnection update(UUID id, String displayName, String rootFolderId, String forcedType,
                                   Boolean writeEnabled, String config, String status) {
        SourceConnection c = connections.findById(id)
                .orElseThrow(() -> new NotFoundException("Connection not found: " + id));
        if (displayName != null) c.setDisplayName(displayName);
        if (rootFolderId != null) c.setRootFolderId(rootFolderId);
        c.setForcedType(forcedType);
        if (writeEnabled != null) c.setWriteEnabled(writeEnabled);
        if (config != null) c.setConfig(config);
        if (status != null) c.setStatus(status);
        audit.record("SOURCE_CONNECTION_UPDATED", "source_connection", id);
        return c;
    }

    public void delete(UUID id) {
        SourceConnection c = connections.findById(id)
                .orElseThrow(() -> new NotFoundException("Connection not found: " + id));
        connections.delete(c);
        audit.record("SOURCE_CONNECTION_DELETED", "source_connection", id);
    }

    @Transactional(readOnly = true)
    public List<ImportFile> imports(UUID connectionId) {
        return ledger.findByConnectionIdOrderByCreatedAtDesc(connectionId);
    }

    /** Outcome of one sync run, surfaced to the admin / payroll screen. {@code issues} names the files
     *  that were flagged (wrong period / unclassified) rather than imported. */
    public record SyncResult(int imported, int needsReview, int skipped, int failed, List<Issue> issues) {
        public record Issue(String filename, String reason) {
        }

        String summary() {
            return imported + " imported, " + needsReview + " to review, " + skipped + " skipped"
                    + (failed > 0 ? ", " + failed + " failed" : "");
        }
    }

    /** Is the tenant sourcing documents of {@code forcedType} (e.g. PAYROLL) from a Drive folder? */
    @Transactional(readOnly = true)
    public boolean driveEnabledFor(String forcedType) {
        return findDriveConnection(forcedType).isPresent();
    }

    /** Full sync of a whole connection (admin "Sync now" on the Data sources screen). */
    public SyncResult sync(UUID connectionId) {
        SourceConnection conn = connections.findById(connectionId)
                .orElseThrow(() -> new NotFoundException("Connection not found: " + connectionId));
        return doSync(conn, null, null, previousMonth(), true);
    }

    /**
     * Sync ONLY the current and previous month for every company (the scheduled poll). One folder
     * listing per run; new previous-month payroll triggers a notification to the company's reps.
     */
    public SyncResult syncRecent(UUID connectionId) {
        SourceConnection conn = connections.findById(connectionId)
                .orElseThrow(() -> new NotFoundException("Connection not found: " + connectionId));
        LocalDate prev = previousMonth();
        LocalDate current = java.time.YearMonth.now(java.time.ZoneOffset.UTC).atDay(1);
        return doSync(conn, null, java.util.Set.of(current, prev), prev, true);
    }

    /**
     * Sync ONLY one company's documents for one month, from the tenant's Drive connection of the given
     * type. Used by the payroll screen's per-company "Sync" button (replaces manual upload).
     */
    public SyncResult syncCompanyMonth(String forcedType, UUID companyId, LocalDate period) {
        SourceConnection conn = findDriveConnection(forcedType)
                .orElseThrow(() -> new NotFoundException("No Drive folder configured for " + forcedType));
        return doSync(conn, companyId, java.util.Set.of(period.withDayOfMonth(1)), previousMonth(), false);
    }

    private Optional<SourceConnection> findDriveConnection(String forcedType) {
        return connections.findByOrderByCreatedAtDesc().stream()
                .filter(c -> "GOOGLE_DRIVE".equalsIgnoreCase(c.getProvider())
                        && forcedType != null && forcedType.equalsIgnoreCase(c.getForcedType()))
                .findFirst();
    }

    /**
     * Core sync loop. {@code onlyCompany}/{@code onlyPeriod} null = full sync; non-null = process only the
     * files resolving to that company/month (others are passed over, not counted). {@code persistStatus}
     * controls whether the connection's cursor/status/summary are updated (only for the full sync).
     */
    private SyncResult doSync(SourceConnection conn, UUID onlyCompany, java.util.Set<LocalDate> onlyPeriods,
                             LocalDate notifyMonth, boolean persistStatus) {
        UUID tenantId = TenantContext.tenantId().orElseThrow();
        CloudFolderConnector connector = registry.forProvider(conn.getProvider());
        List<Company> tenantCompanies = companies.findAll();

        DocumentType forced = parseForcedType(conn.getForcedType());
        int imported = 0, review = 0, skipped = 0, failed = 0;
        List<SyncResult.Issue> issues = new java.util.ArrayList<>();
        java.util.Set<UUID> newPayrollLastMonth = new java.util.HashSet<>();
        Listing listing;
        try {
            listing = connector.list(conn, conn.getCursor());
        } catch (RuntimeException e) {
            log.warn("Listing failed for connection {} ({})", conn.getId(), conn.getProvider(), e);
            if (persistStatus) {
                conn.setStatus("ERROR");
                conn.setLastResult("Listing failed: " + e.getMessage());
                conn.setLastSyncedAt(java.time.Instant.now());
            }
            return new SyncResult(0, 0, 0, 1, List.of());
        }

        for (RemoteFile f : listing.files()) {
            try {
                if (!isSupported(f) || f.size() > MAX_BYTES) {
                    if (onlyCompany == null) skipped++;
                    continue;
                }
                Optional<UUID> companyId = FolderMapper.resolveCompany(f, tenantCompanies);
                LocalDate period = FolderMapper.resolvePeriod(f);
                // Scoped sync: silently pass over files outside the requested company/month.
                if (onlyCompany != null && (companyId.isEmpty() || !companyId.get().equals(onlyCompany))) {
                    continue;
                }
                if (onlyPeriods != null && !onlyPeriods.contains(period)) {
                    continue;
                }
                // Idempotency: same provider file id already seen (and unchanged) → skip.
                Optional<ImportFile> prior = ledger.findByConnectionIdAndSourceRef(conn.getId(), f.id());
                if (prior.isPresent() && java.util.Objects.equals(prior.get().getSourceEtag(), f.etag())) {
                    skipped++;
                    continue;
                }
                if (companyId.isEmpty()) {
                    String reason = "Could not match a company from the folder path";
                    recordReview(tenantId, conn, f, reason);
                    issues.add(new SyncResult.Issue(f.name(), reason));
                    review++;
                    continue;
                }

                // For a payroll folder: the file must be one of the three payroll documents and for the
                // folder's month, otherwise it is flagged (unclassified / wrong period), not imported.
                if (forced == DocumentType.PAYROLL) {
                    if (!looksLikePayroll(f.name())) {
                        String reason = "Unclassified — not a recognised payroll document (pontaj / stat / fluturaș)";
                        recordReview(tenantId, conn, f, reason);
                        issues.add(new SyncResult.Issue(f.name(), reason));
                        review++;
                        continue;
                    }
                    Optional<LocalDate> filePeriod = FolderMapper.periodFromText(f.name());
                    if (filePeriod.isPresent() && !filePeriod.get().equals(period)) {
                        String reason = "Wrong period — file is " + ym(filePeriod.get()) + ", folder month is " + ym(period);
                        recordReview(tenantId, conn, f, reason);
                        issues.add(new SyncResult.Issue(f.name(), reason));
                        review++;
                        continue;
                    }
                }

                byte[] bytes = connector.download(conn, f);
                String sha = sha256(bytes);
                // Content-hash dedupe: same bytes already imported under a different name → skip.
                if (ledger.existsByConnectionIdAndContentSha256(conn.getId(), sha)) {
                    ledger.save(new ImportFile(tenantId, conn.getId(), f.id(), f.etag(), sha,
                            f.name(), f.path(), null, ImportFile.Status.DUPLICATE, "Identical file already imported"));
                    skipped++;
                    continue;
                }

                var doc = documents.upload(companyId.get(), period, f.name(),
                        mime(f), bytes, forced, DocumentSource.DRIVE);
                ledger.save(new ImportFile(tenantId, conn.getId(), f.id(), f.etag(), sha,
                        f.name(), f.path(), doc.getId(), ImportFile.Status.IMPORTED, null));
                imported++;
                if (forced == DocumentType.PAYROLL && period.equals(notifyMonth)) {
                    newPayrollLastMonth.add(companyId.get());
                }
            } catch (RuntimeException e) {
                log.warn("Failed to ingest file {} (conn {})", f.id(), conn.getId(), e);
                failed++;
            }
        }

        // New payroll for the previous month → tell each affected company's representatives.
        for (UUID companyId : newPayrollLastMonth) {
            notifications.notifyCompanyReps(companyId, "PAYROLL_READY",
                    "State de plată disponibile",
                    "Au fost adăugate documentele de salariu pentru " + monthLabel(notifyMonth) + ".");
        }

        SyncResult result = new SyncResult(imported, review, skipped, failed, List.copyOf(issues));
        if (persistStatus) {
            conn.setCursor(listing.nextCursor());
            conn.setStatus("ACTIVE");
            conn.setLastResult(result.summary());
        }
        conn.setLastSyncedAt(java.time.Instant.now());
        audit.record("SOURCE_SYNCED", "source_connection", conn.getId());
        return result;
    }

    private void recordReview(UUID tenantId, SourceConnection conn, RemoteFile f, String detail) {
        ledger.save(new ImportFile(tenantId, conn.getId(), f.id(), f.etag(), null,
                f.name(), f.path(), null, ImportFile.Status.NEEDS_REVIEW, detail));
    }

    /** A payroll folder should only hold pontaj (timesheet), stat de plată, or fluturaș (payslip). */
    private static boolean looksLikePayroll(String name) {
        if (name == null) {
            return false;
        }
        String n = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "").toLowerCase();
        return n.contains("pontaj") || n.contains("fluturas")
                || (n.contains("stat") && (n.contains("salar") || n.contains("plata")));
    }

    private static String ym(LocalDate d) {
        return d.getYear() + "-" + String.format("%02d", d.getMonthValue());
    }

    private static final String[] RO_MONTHS = {"ianuarie", "februarie", "martie", "aprilie", "mai", "iunie",
            "iulie", "august", "septembrie", "octombrie", "noiembrie", "decembrie"};

    private static String monthLabel(LocalDate d) {
        return RO_MONTHS[d.getMonthValue() - 1] + " " + d.getYear();
    }

    private static boolean isSupported(RemoteFile f) {
        String m = mime(f).toLowerCase();
        return m.contains("pdf") || m.startsWith("image/");
    }

    private static String mime(RemoteFile f) {
        if (f.mimeType() != null && !f.mimeType().isBlank()) {
            return f.mimeType();
        }
        String n = f.name() == null ? "" : f.name().toLowerCase();
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }

    private static DocumentType parseForcedType(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return DocumentType.valueOf(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
