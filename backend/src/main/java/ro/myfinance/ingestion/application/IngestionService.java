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

    public IngestionService(SourceConnectionRepository connections, ImportFileRepository ledger,
                            CompanyRepository companies, DocumentService documents,
                            ConnectorRegistry registry, AuditRecorder audit) {
        this.connections = connections;
        this.ledger = ledger;
        this.companies = companies;
        this.documents = documents;
        this.registry = registry;
        this.audit = audit;
    }

    // ---- connection management (TENANT_ADMIN) ----------------------------------------------------

    @Transactional(readOnly = true)
    public List<SourceConnection> list() {
        return connections.findByOrderByCreatedAtDesc();
    }

    public SourceConnection create(String provider, String displayName, String rootFolderId,
                                   String forcedType, String config) {
        UUID tenantId = TenantContext.tenantId().orElseThrow();
        registry.forProvider(provider); // fail fast if the provider has no connector
        SourceConnection c = new SourceConnection(tenantId, provider.toUpperCase(), displayName, rootFolderId, forcedType);
        if (config != null) {
            c.setConfig(config);
        }
        SourceConnection saved = connections.save(c);
        audit.record("SOURCE_CONNECTION_CREATED", "source_connection", saved.getId());
        return saved;
    }

    public SourceConnection update(UUID id, String displayName, String rootFolderId, String forcedType,
                                   String config, String status) {
        SourceConnection c = connections.findById(id)
                .orElseThrow(() -> new NotFoundException("Connection not found: " + id));
        if (displayName != null) c.setDisplayName(displayName);
        if (rootFolderId != null) c.setRootFolderId(rootFolderId);
        c.setForcedType(forcedType);
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

    /** Outcome of one sync run, surfaced to the admin. */
    public record SyncResult(int imported, int needsReview, int skipped, int failed) {
        String summary() {
            return imported + " imported, " + needsReview + " to review, " + skipped + " skipped"
                    + (failed > 0 ? ", " + failed + " failed" : "");
        }
    }

    public SyncResult sync(UUID connectionId) {
        SourceConnection conn = connections.findById(connectionId)
                .orElseThrow(() -> new NotFoundException("Connection not found: " + connectionId));
        UUID tenantId = TenantContext.tenantId().orElseThrow();
        CloudFolderConnector connector = registry.forProvider(conn.getProvider());
        List<Company> tenantCompanies = companies.findAll();

        int imported = 0, review = 0, skipped = 0, failed = 0;
        Listing listing;
        try {
            listing = connector.list(conn, conn.getCursor());
        } catch (RuntimeException e) {
            log.warn("Listing failed for connection {} ({})", connectionId, conn.getProvider(), e);
            conn.setStatus("ERROR");
            conn.setLastResult("Listing failed: " + e.getMessage());
            conn.setLastSyncedAt(java.time.Instant.now());
            return new SyncResult(0, 0, 0, 1);
        }

        for (RemoteFile f : listing.files()) {
            try {
                if (!isSupported(f) || f.size() > MAX_BYTES) {
                    skipped++;
                    continue;
                }
                // Idempotency: same provider file id already seen (and unchanged) → skip.
                Optional<ImportFile> prior = ledger.findByConnectionIdAndSourceRef(conn.getId(), f.id());
                if (prior.isPresent() && java.util.Objects.equals(prior.get().getSourceEtag(), f.etag())) {
                    skipped++;
                    continue;
                }

                Optional<UUID> companyId = FolderMapper.resolveCompany(f, tenantCompanies);
                if (companyId.isEmpty()) {
                    recordReview(tenantId, conn, f, null, "Could not match a company from the folder path");
                    review++;
                    continue;
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

                LocalDate period = FolderMapper.resolvePeriod(f);
                DocumentType forced = parseForcedType(conn.getForcedType());
                var doc = documents.upload(companyId.get(), period, f.name(),
                        mime(f), bytes, forced, DocumentSource.DRIVE);
                ledger.save(new ImportFile(tenantId, conn.getId(), f.id(), f.etag(), sha,
                        f.name(), f.path(), doc.getId(), ImportFile.Status.IMPORTED, null));
                imported++;
            } catch (RuntimeException e) {
                log.warn("Failed to ingest file {} (conn {})", f.id(), connectionId, e);
                failed++;
            }
        }

        conn.setCursor(listing.nextCursor());
        conn.setStatus("ACTIVE");
        conn.setLastSyncedAt(java.time.Instant.now());
        SyncResult result = new SyncResult(imported, review, skipped, failed);
        conn.setLastResult(result.summary());
        audit.record("SOURCE_SYNCED", "source_connection", conn.getId());
        return result;
    }

    private void recordReview(UUID tenantId, SourceConnection conn, RemoteFile f, UUID docId, String detail) {
        ledger.save(new ImportFile(tenantId, conn.getId(), f.id(), f.etag(), null,
                f.name(), f.path(), docId, ImportFile.Status.NEEDS_REVIEW, detail));
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
