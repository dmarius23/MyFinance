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
    private final ModuleSyncStatusService syncStatus;
    private final java.util.concurrent.Executor moduleSyncExecutor;

    public IngestionService(SourceConnectionRepository connections, ImportFileRepository ledger,
                            CompanyDirectory companies, DocumentService documents,
                            ConnectorRegistry registry, AuditRecorder audit,
                            ro.myfinance.notifications.application.NotificationService notifications,
                            ModuleSyncStatusService syncStatus,
                            @org.springframework.beans.factory.annotation.Qualifier(
                                    ro.myfinance.common.async.AsyncConfig.MODULE_SYNC) java.util.concurrent.Executor moduleSyncExecutor) {
        this.connections = connections;
        this.ledger = ledger;
        this.companies = companies;
        this.documents = documents;
        this.registry = registry;
        this.audit = audit;
        this.notifications = notifications;
        this.syncStatus = syncStatus;
        this.moduleSyncExecutor = moduleSyncExecutor;
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
                                   String forcedType, boolean writeEnabled, String purpose,
                                   boolean rootIsYearFolder, String config) {
        UUID tenantId = TenantContext.tenantId().orElseThrow();
        registry.forProvider(provider); // fail fast if the provider has no connector
        SourceConnection c = new SourceConnection(tenantId, provider.toUpperCase(), displayName, rootFolderId, forcedType);
        c.setWriteEnabled(writeEnabled);
        c.setPurpose(purpose);
        c.setRootIsYearFolder(rootIsYearFolder);
        if (config != null) {
            c.setConfig(config);
        }
        SourceConnection saved = connections.save(c);
        audit.record("SOURCE_CONNECTION_CREATED", "source_connection", saved.getId());
        return saved;
    }

    public SourceConnection update(UUID id, String displayName, String rootFolderId, String forcedType,
                                   Boolean writeEnabled, String purpose, Boolean rootIsYearFolder,
                                   String config, String status) {
        SourceConnection c = connections.findById(id)
                .orElseThrow(() -> new NotFoundException("Connection not found: " + id));
        if (displayName != null) c.setDisplayName(displayName);
        if (rootFolderId != null) c.setRootFolderId(rootFolderId);
        c.setForcedType(forcedType);
        if (writeEnabled != null) c.setWriteEnabled(writeEnabled);
        if (purpose != null) c.setPurpose(purpose);
        if (rootIsYearFolder != null) c.setRootIsYearFolder(rootIsYearFolder);
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
        return doSync(conn, null, null, null, previousMonth(), true, conn.getRootFolderId(), false, null, null);
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
        return doSync(conn, null, java.util.Set.of(current, prev), null, prev, true, conn.getRootFolderId(), false, null, null);
    }

    /**
     * Sync ONLY one company's documents for one month, from the tenant's Drive connection of the given
     * type. Used by the payroll screen's per-company "Sync" button (replaces manual upload).
     */
    public SyncResult syncCompanyMonth(String forcedType, UUID companyId, LocalDate period) {
        SourceConnection conn = findDriveConnection(forcedType)
                .orElseThrow(() -> new NotFoundException("No Drive folder configured for " + forcedType));
        // Reject a per-company sync while a month-wide sync (all companies) for the same module/month runs,
        // so the two don't process the same company at once (the bulk sync holds the shared slot).
        DocumentType syncModule = parseForcedType(forcedType);
        if (syncModule != null && syncStatus.isRunning(syncModule.name(), statusPeriod(syncModule, period))) {
            throw new ro.myfinance.common.web.ConflictException(
                    "A month-wide sync for " + syncModule + " is already running — try again once it finishes.");
        }
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
                previousMonth(), false, startFolder, companyKnown, null, null);
        log.info("syncCompanyMonth done company={} period={} → imported={} review={} skipped={} failed={}",
                companyId, period, r.imported(), r.needsReview(), r.skipped(), r.failed());
        return r;
    }

    /**
     * Sync ONE module (payroll / declarations / trial balance) for a whole month across ALL companies —
     * the firm's monthly bulk pull. Scopes the crawl to that module's month subtree (fast: one month
     * folder, not the whole drive) and imports only that module's type, so a payroll sync does not pull
     * the same month's declarations. Requires the month-first layout.
     */
    public ModuleSyncStatusService.View syncModuleMonth(String moduleType, LocalDate period) {
        DocumentType module = parseForcedType(moduleType);
        if (module == null) {
            throw new IllegalArgumentException("Unknown module type: " + moduleType);
        }
        SourceConnection conn = findDriveConnection(moduleType)
                .orElseThrow(() -> new NotFoundException("No Drive folder configured for " + moduleType));
        LocalDate month = period.withDayOfMonth(1);
        String startFolder;
        LocalDate targetPeriod;
        if (module == DocumentType.TRIAL_BALANCE) {
            int quarter = (month.getMonthValue() - 1) / 3 + 1;
            targetPeriod = LocalDate.of(month.getYear(), quarter * 3, 1); // interim balance is keyed to quarter-end
            startFolder = findBilantFolder(conn, month.getYear(), quarter);
        } else {
            targetPeriod = month;
            startFolder = findMonthFolder(conn, month);
        }
        log.info("syncModuleMonth module={} period={} connection={} startFolder={}",
                module, targetPeriod, conn.getId(), startFolder);
        // Atomically claim the slot: reject a second concurrent sync of the same module/month. The claim
        // commits immediately so every user sees the in-progress sync; it is released (with the result and
        // last-synced time) when this run ends, even on failure or an empty folder.
        UUID startedBy = TenantContext.current().map(TenantContext.Identity::userId).orElse(null);
        if (!syncStatus.tryStart(module.name(), targetPeriod, startedBy)) {
            throw new ro.myfinance.common.web.ConflictException(
                    "A sync for " + module + " / " + targetPeriod + " is already running.");
        }
        // Run the crawl + import off the request thread so a big first sync never blocks/times out the HTTP
        // call. Progress is shared via module_sync_status (polled by every module screen). Tenant context is
        // propagated to the worker. In tests (myfinance.async.inline=true) this runs inline before returning.
        LocalDate finalTargetPeriod = targetPeriod;
        String finalStartFolder = startFolder;
        moduleSyncExecutor.execute(() -> runModuleSync(conn, module, finalTargetPeriod, finalStartFolder));
        // Return the current shared state: RUNNING on a background thread (prod), or the finished result (tests).
        return syncStatus.get(module.name(), targetPeriod);
    }

    /** The crawl + import for a claimed (module, month) slot; always releases the slot via markFinish. */
    private void runModuleSync(SourceConnection conn, DocumentType module, LocalDate targetPeriod, String startFolder) {
        SyncResult r = new SyncResult(0, 0, 0, 0, List.of());
        try {
            if (startFolder == null) {
                log.warn("syncModuleMonth: no {} folder found under root for {} — nothing to sync", module, targetPeriod);
                return;
            }
            r = doSync(conn, null, java.util.Set.of(targetPeriod), module, previousMonth(),
                    false, startFolder, false, module, targetPeriod);
            log.info("syncModuleMonth done module={} period={} → imported={} review={} skipped={} failed={}",
                    module, targetPeriod, r.imported(), r.needsReview(), r.skipped(), r.failed());
        } catch (RuntimeException e) {
            log.error("syncModuleMonth failed module={} period={}: {}", module, targetPeriod, e.getMessage(), e);
            r = new SyncResult(r.imported(), r.needsReview(), r.skipped(), r.failed() + 1, r.issues());
        } finally {
            syncStatus.markFinish(module.name(), targetPeriod, r.summary());
        }
    }

    /** The status key period for a module + requested month: interim balances are keyed to the quarter-end
     *  month (T2 → June), everything else to the month itself — matching {@link #syncModuleMonth}. */
    public static LocalDate statusPeriod(DocumentType module, LocalDate period) {
        LocalDate month = period.withDayOfMonth(1);
        if (module == DocumentType.TRIAL_BALANCE) {
            int quarter = (month.getMonthValue() - 1) / 3 + 1;
            return LocalDate.of(month.getYear(), quarter * 3, 1);
        }
        return month;
    }

    /** The shared sync state for a module + month (last synced, and whether a sync is running now). */
    @Transactional(readOnly = true)
    public ModuleSyncStatusService.View syncStatusFor(String type, LocalDate period) {
        DocumentType module = parseForcedType(type);
        if (module == null) {
            return new ModuleSyncStatusService.View(false, null, null, null, null);
        }
        return syncStatus.get(module.name(), statusPeriod(module, period));
    }

    /** The root subfolder that is the month folder for {@code month} (e.g. "7. Iulie 2026"), or null. */
    private String findMonthFolder(SourceConnection conn, LocalDate month) {
        try {
            return registry.forProvider(conn.getProvider()).subfolders(conn, conn.getRootFolderId()).stream()
                    .filter(sf -> isMonthFolderFor(sf.name(), month))
                    .map(CloudFolderConnector.Folder::id).findFirst().orElse(null);
        } catch (RuntimeException e) {
            log.warn("Could not list root subfolders for connection {}", conn.getId(), e);
            return null;
        }
    }

    /** The root subfolder that is the interim-balance folder for a quarter (e.g. "Bilant interimar T2 an 2026"). */
    private String findBilantFolder(SourceConnection conn, int year, int quarter) {
        try {
            return registry.forProvider(conn.getProvider()).subfolders(conn, conn.getRootFolderId()).stream()
                    .filter(sf -> isBilantFolderFor(sf.name(), year, quarter))
                    .map(CloudFolderConnector.Folder::id).findFirst().orElse(null);
        } catch (RuntimeException e) {
            log.warn("Could not list root subfolders for connection {}", conn.getId(), e);
            return null;
        }
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
                             String startFolderId, boolean companyKnown, DocumentType onlyType,
                             LocalDate forcedPeriod) {
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
        // The module-month sync ({@code onlyType} set) targets one module's subtree for the whole firm; it
        // is inherently a month-first operation, so apply those guards even if the config flag is absent.
        boolean monthFirst = monthFirstLayout(conn) || onlyType != null;
        // The root-subtree scope check (only month/balance folders) only applies to a full crawl FROM the
        // root. When we start below the root (a company or a scoped module subtree), the paths are already
        // inside the wanted subtree and carry no top month/Bilant segment, so that check would drop them.
        boolean rootCrawl = startFolderId != null && startFolderId.equals(conn.getRootFolderId());
        java.util.Set<String> supersededBySigned = monthFirst
                ? signedSupersededKeys(listing.files()) : java.util.Set.of();
        // The firm stores each declaration under two filenames: the ANAF original "D<code>_<CUI>_<YYYY>_<MM>"
        // and a renamed copy "<Company>_D<code>_<MMYYYY>_<CUI>". Both import as separate documents. Collect
        // the (company + folder + form) identities that have an ANAF-named original, so the renamed copy is
        // dropped below and only one document per declaration is created.
        java.util.Set<String> anafNamedDeclKeys = monthFirst
                ? anafDeclarationKeys(listing.files(), tenantCompanies) : java.util.Set.of();
        // Payroll kept as draft + final ("…_Initial.pdf" and "…_final.pdf"): when a final version exists for
        // the same company + folder + payroll doc-kind, drop the non-final one so only the final imports.
        java.util.Set<String> finalPayrollKeys = monthFirst
                ? finalPayrollKeys(listing.files(), tenantCompanies) : java.util.Set.of();

        for (RemoteFile f : listing.files()) {
            try {
                if (!isSupported(f) || f.size() > MAX_BYTES) {
                    if (onlyCompany == null) skipped++;
                    continue;
                }
                // Month-first layout guards (opt-in): ignore anything outside the month/balance subtrees,
                // drop ANAF "recipisa" receipts, and skip an unsigned copy when a "…semnat" one exists.
                if (monthFirst) {
                    if ((rootCrawl && !inMonthFirstScope(f)) || isRecipisa(f.name())
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
                // Module-month sync: import only this module's type; silently pass over the rest of the
                // subtree (e.g. a payroll sync ignores the D-form declarations in the same month folder).
                if (onlyType != null && fileType != onlyType) {
                    continue;
                }
                // Declaration dedup: drop the renamed copy ("<Company>_D<code>_…") when the ANAF original
                // ("D<code>_<CUI>_…") of the same declaration is in this crawl — avoids importing it twice.
                if (monthFirst && fileType == DocumentType.DECLARATION && companyId.isPresent()
                        && !isAnafNamedDeclaration(f.name())) {
                    String form = declarationFormCode(f.name());
                    if (form != null && anafNamedDeclKeys.contains(declarationKey(companyId.get(), f, form))) {
                        if (onlyCompany == null) skipped++;
                        continue;
                    }
                }
                // Payroll dedup: drop a non-final copy ("…_Initial") when a "…_final" of the same company +
                // folder + payroll doc-kind (fluturaș / stat / pontaj) is present in this crawl.
                if (monthFirst && fileType == DocumentType.PAYROLL && companyId.isPresent() && !isFinalVariant(f.name())) {
                    String kind = payrollDocKind(f.name());
                    if (kind != null && finalPayrollKeys.contains(payrollKey(companyId.get(), f, kind))) {
                        if (onlyCompany == null) skipped++;
                        continue;
                    }
                }
                // Period: forced by a module-month sync (we already scoped to that month's subtree, whose
                // name is no longer in the relative path); otherwise from the folder path, with the
                // filename's own YYYY_MM winning for declarations/balances (filed under a later month).
                LocalDate period;
                if (forcedPeriod != null) {
                    period = forcedPeriod;
                } else {
                    period = FolderMapper.resolvePeriod(f);
                    if (monthFirst && (fileType == DocumentType.DECLARATION || fileType == DocumentType.TRIAL_BALANCE)) {
                        period = FolderMapper.periodFromText(f.name()).orElse(period);
                    }
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
                // Re-import of a file whose Drive content changed (etag differs, bytes differ): the file was
                // updated in place, so replace the previously imported version rather than leaving a duplicate.
                if (prior != null && ImportFile.Status.IMPORTED.name().equals(prior.getStatus())
                        && prior.getDocumentId() != null && !prior.getDocumentId().equals(doc.getId())) {
                    try {
                        documents.delete(prior.getDocumentId());
                    } catch (RuntimeException e) {
                        log.warn("Could not remove superseded document {} on re-import of {}", prior.getDocumentId(), f.name(), e);
                    }
                }
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

    /** Whether a root subfolder is the month folder for {@code month} — carries the year and RO month name. */
    private static boolean isMonthFolderFor(String seg, LocalDate month) {
        String n = normalizeSeg(seg);
        return n.contains(String.valueOf(month.getYear()))
                && n.contains(RO_MONTH_TOKENS.get(month.getMonthValue() - 1));
    }

    /** Whether a root subfolder is the interim-balance folder for a given year + trimester. */
    private static boolean isBilantFolderFor(String seg, int year, int quarter) {
        String n = normalizeSeg(seg);
        return n.startsWith("bilant") && n.contains("t" + quarter) && n.contains(String.valueOf(year));
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

    /** A declaration form code ("D112", "D300", "D406" …) found in a filename, in either the ANAF format
     *  {@code D<code>_<CUI>_…} or the renamed {@code <Company>_D<code>_…}; null if none. */
    private static final java.util.regex.Pattern DECL_FORM_CODE =
            java.util.regex.Pattern.compile("(?i)(?<![a-z0-9])d\\s?(\\d{2,4})(?![0-9])");
    /** An ANAF-generated declaration filename: starts with the form code then the CUI ({@code D112_49443957_…}). */
    private static final java.util.regex.Pattern ANAF_NAMED_DECL =
            java.util.regex.Pattern.compile("(?i)^\\s*d\\s?\\d{2,4}[_ ]\\d{5,}");

    private static String declarationFormCode(String name) {
        if (name == null) {
            return null;
        }
        java.util.regex.Matcher m = DECL_FORM_CODE.matcher(name);
        return m.find() ? "D" + m.group(1) : null;
    }

    private static boolean isAnafNamedDeclaration(String name) {
        return name != null && ANAF_NAMED_DECL.matcher(name.trim()).find();
    }

    /** Identity of a declaration for dedup: company + its folder + form code (A/B copies share all three). */
    private static String declarationKey(UUID companyId, RemoteFile f, String form) {
        return companyId + "|" + (f.path() == null ? "" : f.path().toLowerCase()) + "|" + form;
    }

    /** (company + folder + form) identities that have an ANAF-named original in this crawl. */
    private static java.util.Set<String> anafDeclarationKeys(List<RemoteFile> files, List<Company> companies) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (RemoteFile f : files) {
            if (!isAnafNamedDeclaration(f.name())) {
                continue;
            }
            String form = declarationFormCode(f.name());
            if (form == null) {
                continue;
            }
            FolderMapper.resolveCompany(f, companies).ifPresent(co -> keys.add(declarationKey(co, f, form)));
        }
        return keys;
    }

    /** Whether a filename marks a "final" version ("…final" / "…finala"). */
    private static boolean isFinalVariant(String name) {
        return name != null && normalizeSeg(name).contains("final");
    }

    /** The payroll doc-kind in a filename (fluturas / stat / pontaj / tichete), else null. */
    private static String payrollDocKind(String name) {
        if (name == null) {
            return null;
        }
        String n = normalizeSeg(name);
        if (n.contains("pontaj")) {
            return "pontaj";
        }
        if (n.contains("fluturas")) {
            return "fluturas";
        }
        if (n.contains("stat") && (n.contains("salar") || n.contains("plata"))) {
            return "stat";
        }
        if (n.contains("tichet")) {
            return "tichete";
        }
        return null;
    }

    /** Identity of a payroll document for prefer-final dedup: company + its folder + doc-kind. */
    private static String payrollKey(UUID companyId, RemoteFile f, String kind) {
        return companyId + "|" + (f.path() == null ? "" : f.path().toLowerCase()) + "|" + kind;
    }

    /** (company + folder + payroll doc-kind) identities that have a "…final" version in this crawl. */
    private static java.util.Set<String> finalPayrollKeys(List<RemoteFile> files, List<Company> companies) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (RemoteFile f : files) {
            if (!isFinalVariant(f.name())) {
                continue;
            }
            String kind = payrollDocKind(f.name());
            if (kind == null) {
                continue;
            }
            FolderMapper.resolveCompany(f, companies).ifPresent(co -> keys.add(payrollKey(co, f, kind)));
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
