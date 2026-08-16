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
import ro.myfinance.company.application.CompanyDirectory;
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
    private final CompanyDirectory companies;
    private final DocumentService documents;
    private final ConnectorRegistry registry;
    private final AuditRecorder audit;
    private final ro.myfinance.notifications.application.NotificationService notifications;

    public IngestionService(SourceConnectionRepository connections, ImportFileRepository ledger,
                            CompanyDirectory companies, DocumentService documents,
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

    /** Whether a Drive connection covers this type, and whether it is write-enabled (mirrors uploads). */
    @Transactional(readOnly = true)
    public DriveStatus driveStatusFor(String forcedType) {
        return findDriveConnection(forcedType)
                .map(c -> new DriveStatus(true, c.isWriteEnabled()))
                .orElse(new DriveStatus(false, false));
    }

    public record DriveStatus(boolean enabled, boolean write) {
    }

    /** Full sync of a whole connection (admin "Sync now" on the Data sources screen). */
    public SyncResult sync(UUID connectionId) {
        SourceConnection conn = connections.findById(connectionId)
                .orElseThrow(() -> new NotFoundException("Connection not found: " + connectionId));
        return doSync(conn, null, null, null, previousMonth(), true, conn.getRootFolderId(), false);
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
        return doSync(conn, null, java.util.Set.of(current, prev), null, prev, true, conn.getRootFolderId(), false);
    }

    /**
     * Sync ONLY one company's documents for one month, from the tenant's Drive connection of the given
     * type. Used by the payroll screen's per-company "Sync" button (replaces manual upload).
     */
    public SyncResult syncCompanyMonth(String forcedType, UUID companyId, LocalDate period) {
        SourceConnection conn = findDriveConnection(forcedType)
                .orElseThrow(() -> new NotFoundException("No Drive folder configured for " + forcedType));
        // Fast path: crawl only this company's folder under the root instead of the whole drive. Falls
        // back to a full crawl when the company folder can't be located (then the file paths still carry
        // the company name for per-file resolution).
        Company company = companies.findById(companyId).orElse(null);
        String startFolder = conn.getRootFolderId();
        boolean companyKnown = false;
        // The month-first layout has no per-company folder under the root (the company lives in the
        // filename or a Bilant sub-folder), so always full-crawl and resolve the company per file.
        String companyFolderId = (company == null || monthFirstLayout(conn)) ? null : findCompanyFolder(conn, company);
        if (companyFolderId != null) {
            startFolder = companyFolderId;
            companyKnown = true;
        }
        log.info("syncCompanyMonth type={} company={} period={} connection={} companyFolder={} (scoped={})",
                forcedType, companyId, period, conn.getId(), companyFolderId, companyKnown);
        // Synced from a type-specific screen (payroll/reports/declarations): files not filed in a type
        // sub-folder default to that type, so a document dropped straight in the month folder is still typed.
        SyncResult r = doSync(conn, companyId, java.util.Set.of(period.withDayOfMonth(1)), parseForcedType(forcedType),
                previousMonth(), false, startFolder, companyKnown);
        log.info("syncCompanyMonth done company={} period={} → imported={} review={} skipped={} failed={}",
                companyId, period, r.imported(), r.needsReview(), r.skipped(), r.failed());
        return r;
    }

    /** The Drive folder id for {@code company} directly under the connection root, or null if not found. */
    private String findCompanyFolder(SourceConnection conn, Company company) {
        try {
            return registry.forProvider(conn.getProvider()).subfolders(conn, conn.getRootFolderId()).stream()
                    .filter(sf -> FolderMapper.matchesCompany(sf.name(), company))
                    .map(CloudFolderConnector.Folder::id)
                    .findFirst().orElse(null);
        } catch (RuntimeException e) {
            log.warn("Could not list root subfolders for connection {} — full crawl", conn.getId(), e);
            return null;
        }
    }

    private Optional<SourceConnection> findDriveConnection(String forcedType) {
        List<SourceConnection> drive = connections.findByOrderByCreatedAtDesc().stream()
                .filter(c -> "GOOGLE_DRIVE".equalsIgnoreCase(c.getProvider()) && !"DISABLED".equals(c.getStatus()))
                .toList();
        // A type-specific connection wins; otherwise a general root connection (no forced type) covers all types.
        return drive.stream()
                .filter(c -> forcedType != null && forcedType.equalsIgnoreCase(c.getForcedType()))
                .findFirst()
                .or(() -> drive.stream()
                        .filter(c -> c.getForcedType() == null || c.getForcedType().isBlank())
                        .findFirst());
    }

    /**
     * Core sync loop. {@code onlyCompany}/{@code onlyPeriod} null = full sync; non-null = process only the
     * files resolving to that company/month (others are passed over, not counted). {@code persistStatus}
     * controls whether the connection's cursor/status/summary are updated (only for the full sync).
     */
    private SyncResult doSync(SourceConnection conn, UUID onlyCompany, java.util.Set<LocalDate> onlyPeriods,
                             DocumentType fallbackType, LocalDate notifyMonth, boolean persistStatus,
                             String startFolderId, boolean companyKnown) {
        UUID tenantId = TenantContext.tenantId().orElseThrow();
        CloudFolderConnector connector = registry.forProvider(conn.getProvider());
        List<Company> tenantCompanies = companies.findAll();

        DocumentType forced = parseForcedType(conn.getForcedType());
        int imported = 0, review = 0, skipped = 0, failed = 0;
        List<SyncResult.Issue> issues = new java.util.ArrayList<>();
        java.util.Set<UUID> newPayrollLastMonth = new java.util.HashSet<>();
        Listing listing;
        try {
            listing = connector.list(conn, startFolderId, conn.getCursor());
        } catch (RuntimeException e) {
            log.warn("Listing failed for connection {} ({})", conn.getId(), conn.getProvider(), e);
            if (persistStatus) {
                conn.setStatus("ERROR");
                conn.setLastResult("Listing failed: " + e.getMessage());
                conn.setLastSyncedAt(java.time.Instant.now());
            }
            return new SyncResult(0, 0, 0, 1, List.of());
        }
        log.info("doSync connection={} startFolder={} companyKnown={} → {} file(s) listed",
                conn.getId(), startFolderId, companyKnown, listing.files().size());

        // A "month-first" layout (e.g. Declaratii <year>/<month>/State de plata|D-forms, plus Bilant
        // interimar <trimester>/<company>/balanta de verificare) needs extra guards the generic
        // company-first layout doesn't: only crawl month/balance folders, drop ANAF receipts, and keep
        // only the signed copy when both signed & unsigned exist. Off by default (opt-in via config).
        boolean monthFirst = monthFirstLayout(conn);
        java.util.Set<String> supersededBySigned = monthFirst
                ? signedSupersededKeys(listing.files()) : java.util.Set.of();

        for (RemoteFile f : listing.files()) {
            try {
                if (!isSupported(f) || f.size() > MAX_BYTES) {
                    if (onlyCompany == null) skipped++;
                    continue;
                }
                // Month-first layout guards (opt-in): ignore anything outside the month/balance subtrees,
                // drop ANAF "recipisa" receipts, and skip an unsigned copy when a "…semnat" one exists.
                if (monthFirst) {
                    if (!inMonthFirstScope(f) || isRecipisa(f.name())
                            || (!isSigned(f.name()) && supersededBySigned.contains(dedupKey(f)))) {
                        if (onlyCompany == null) skipped++;
                        continue;
                    }
                }
                Optional<UUID> companyId = companyKnown
                        ? Optional.of(onlyCompany)
                        : FolderMapper.resolveCompany(f, tenantCompanies);
                // Type up front (it can refine the period): a connection-level forced type wins; else the
                // type sub-folder; else — only in the month-first layout — the filename (loose files with
                // no type folder); else the screen's type for a per-type sync; else the classifier decides.
                DocumentType fileType;
                if (forced != null) {
                    fileType = forced;
                } else {
                    Optional<DocumentType> folderType = FolderMapper.resolveType(f);
                    if (folderType.isPresent()) {
                        fileType = folderType.get();
                    } else if (monthFirst) {
                        fileType = ro.myfinance.intake.domain.DriveDocLayout.typeOfFileName(f.name()).orElse(fallbackType);
                    } else {
                        fileType = fallbackType;
                    }
                }
                // Period from the folder path, but for declarations/balances the filename's own YYYY_MM is
                // authoritative (a file prepared in one month may be filed under the next month's folder).
                LocalDate period = FolderMapper.resolvePeriod(f);
                if (monthFirst && (fileType == DocumentType.DECLARATION || fileType == DocumentType.TRIAL_BALANCE)) {
                    period = FolderMapper.periodFromText(f.name()).orElse(period);
                }
                // Scoped sync: silently pass over files outside the requested company/month.
                if (onlyCompany != null && (companyId.isEmpty() || !companyId.get().equals(onlyCompany))) {
                    continue;
                }
                if (onlyPeriods != null && !onlyPeriods.contains(period)) {
                    continue;
                }
                // Idempotency: skip only files already IMPORTED and unchanged. A file previously flagged
                // (needs-review / duplicate) is re-evaluated on each sync (the situation may have changed).
                ImportFile prior = ledger.findByConnectionIdAndSourceRef(conn.getId(), f.id()).orElse(null);
                if (prior != null && ImportFile.Status.IMPORTED.name().equals(prior.getStatus())
                        && java.util.Objects.equals(prior.getSourceEtag(), f.etag())) {
                    skipped++;
                    continue;
                }
                if (companyId.isEmpty()) {
                    String reason = "Could not match a company from the folder path";
                    writeLedger(prior, tenantId, conn, f, null, null, period, null, ImportFile.Status.NEEDS_REVIEW, reason);
                    issues.add(new SyncResult.Issue(f.name(), reason));
                    review++;
                    continue;
                }

                // For a payroll document: it must be one of the three payroll files and for the folder's
                // month, otherwise it is flagged (unclassified / wrong period), not imported.
                if (fileType == DocumentType.PAYROLL) {
                    if (!looksLikePayroll(f.name())) {
                        String reason = "Unclassified — not a recognised payroll document (pontaj / stat / fluturaș)";
                        writeLedger(prior, tenantId, conn, f, null, companyId.get(), period, null, ImportFile.Status.NEEDS_REVIEW, reason);
                        issues.add(new SyncResult.Issue(f.name(), reason));
                        review++;
                        continue;
                    }
                    Optional<LocalDate> filePeriod = FolderMapper.periodFromText(f.name());
                    if (filePeriod.isPresent() && !filePeriod.get().equals(period)) {
                        String reason = "Wrong period — file is " + ym(filePeriod.get()) + ", folder month is " + ym(period);
                        writeLedger(prior, tenantId, conn, f, null, companyId.get(), period, null, ImportFile.Status.NEEDS_REVIEW, reason);
                        issues.add(new SyncResult.Issue(f.name(), reason));
                        review++;
                        continue;
                    }
                }

                byte[] bytes = connector.download(conn, f);
                String sha = sha256(bytes);
                // Content-hash dedupe scoped to this company + period: identical bytes already imported for
                // the SAME company/month → skip. The same bytes in another month import as their own document.
                if (ledger.existsByConnectionIdAndCompanyIdAndPeriodMonthAndContentSha256AndStatus(
                        conn.getId(), companyId.get(), period, sha, ImportFile.Status.IMPORTED.name())) {
                    writeLedger(prior, tenantId, conn, f, sha, companyId.get(), period, null,
                            ImportFile.Status.DUPLICATE, "Identical file already imported for this month");
                    skipped++;
                    continue;
                }

                var doc = documents.upload(companyId.get(), period, f.name(),
                        mime(f), bytes, fileType, DocumentSource.DRIVE);
                writeLedger(prior, tenantId, conn, f, sha, companyId.get(), period, doc.getId(), ImportFile.Status.IMPORTED, null);
                imported++;
                if (fileType == DocumentType.PAYROLL && period.equals(notifyMonth)) {
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

    /** Upsert a ledger row: re-record the existing one on re-sync (unique per connection + file), else insert. */
    private void writeLedger(ImportFile prior, UUID tenantId, SourceConnection conn, RemoteFile f, String sha,
                             UUID companyId, LocalDate period, UUID documentId, ImportFile.Status status, String detail) {
        if (prior != null) {
            prior.record(f.etag(), sha, f.name(), f.path(), companyId, period, documentId, status, detail);
        } else {
            ledger.save(new ImportFile(tenantId, conn.getId(), f.id(), f.etag(), sha,
                    f.name(), f.path(), companyId, period, documentId, status, detail));
        }
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

    // ---- month-first layout helpers (opt-in via connection config "month_first") ------------------

    /** RO month names used to recognise a month folder such as "1. Ianuarie 2026" / "7. Iulie 2026". */
    private static final List<String> RO_MONTH_TOKENS = List.of(
            "ianuarie", "februarie", "martie", "aprilie", "mai", "iunie",
            "iulie", "august", "septembrie", "octombrie", "noiembrie", "decembrie");

    /** Whether this connection uses the month-first layout (month/type folders + a Bilant balance tree). */
    private static boolean monthFirstLayout(SourceConnection conn) {
        String cfg = conn.getConfig();
        return cfg != null && cfg.toLowerCase().contains("month_first");
    }

    /**
     * Only crawl the month folders and the interim-balance ("Bilant interimar Tn an YYYY/&lt;company&gt;/…")
     * tree. In a Bilant folder the trial balance is a plain file named "balanta_de_verificare …" sitting
     * directly in the company folder (alongside the full statement, AGA decision, etc.), though some
     * companies may instead use a "balanta de verificare" sub-folder — accept either. Everything that is
     * not the trial balance (the "Bilant …" statement, "Hotarare AGA", profit proposal) is left out.
     */
    private static boolean inMonthFirstScope(RemoteFile f) {
        List<String> segs = pathSegments(f.path());
        if (segs.isEmpty()) {
            return false;
        }
        String top = segs.get(0);
        if (isMonthFolder(top)) {
            return true;
        }
        if (isBilantFolder(top)) {
            return segs.stream().anyMatch(IngestionService::isBalantaFolder) || isBalantaFolder(f.name());
        }
        return false;
    }

    private static boolean isMonthFolder(String seg) {
        String n = normalizeSeg(seg);
        if (!n.matches(".*20\\d{2}.*")) {   // a month folder always carries its year
            return false;
        }
        return RO_MONTH_TOKENS.stream().anyMatch(n::contains);
    }

    private static boolean isBilantFolder(String seg) {
        return normalizeSeg(seg).startsWith("bilant");
    }

    private static boolean isBalantaFolder(String seg) {
        String n = normalizeSeg(seg);
        return n.startsWith("balanta") || n.startsWith("balante");
    }

    /** An ANAF submission receipt ("…_recipisa_<nr>.pdf") — proof of filing, not the declaration itself. */
    private static boolean isRecipisa(String name) {
        return name != null && normalizeSeg(name).contains("recipisa");
    }

    /** A signed copy ("…_semnat.pdf") — preferred over the unsigned original when both are present. */
    private static boolean isSigned(String name) {
        return name != null && normalizeSeg(name).contains("semnat");
    }

    /** Dedup keys of the documents that have a signed ("…semnat") copy — their unsigned twins are skipped. */
    private static java.util.Set<String> signedSupersededKeys(List<RemoteFile> files) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (RemoteFile f : files) {
            if (isSigned(f.name())) {
                keys.add(dedupKey(f));
            }
        }
        return keys;
    }

    /** A stable identity for a document independent of the "semnat" marker, so signed & unsigned collide. */
    private static String dedupKey(RemoteFile f) {
        String name = f.name() == null ? "" : stripDiacritics(f.name()).toLowerCase().trim();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        name = name.replaceAll("[ _-]*semnat", "").replaceAll("\\s+", " ").trim();
        String path = f.path() == null ? "" : f.path().toLowerCase();
        return path + "|" + name;
    }

    private static List<String> pathSegments(String path) {
        if (path == null || path.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(path.split("/")).filter(s -> !s.isBlank()).toList();
    }

    private static String stripDiacritics(String s) {
        String t = s.replace('ș', 's').replace('ț', 't').replace('Ș', 'S').replace('Ț', 'T');
        return java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }

    /** Lowercase, diacritics dropped, non-alphanumerics removed — for tolerant folder/marker matching. */
    private static String normalizeSeg(String s) {
        return s == null ? "" : stripDiacritics(s).toLowerCase().replaceAll("[^a-z0-9]", "");
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
