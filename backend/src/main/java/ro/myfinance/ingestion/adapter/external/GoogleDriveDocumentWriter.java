package ro.myfinance.ingestion.adapter.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ro.myfinance.intake.application.DriveDocumentWriter;

/**
 * Writes document bytes into a tenant's Google Shared Drive (mirror target). Resolves — creating if
 * missing — the {@code root / company / YYYY-MM / type} folder path, then uploads via Drive v3 multipart.
 * Files are tagged {@code appProperties.myfinanceDocId} so ingestion never re-imports an app-written file.
 * All calls use {@code supportsAllDrives=true} + the shared drive's {@code driveId}. Read-only ingestion
 * keeps its own scope; this adapter mints a write-scoped token.
 */
@Component
public class GoogleDriveDocumentWriter implements DriveDocumentWriter {

    private static final Logger log = LoggerFactory.getLogger(GoogleDriveDocumentWriter.class);
    private static final String WRITE_SCOPE = "https://www.googleapis.com/auth/drive";
    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";

    private final GoogleDriveTokens tokens;
    private final RestClient http;
    private final ObjectMapper json = new ObjectMapper();

    public GoogleDriveDocumentWriter(GoogleDriveTokens tokens, RestClient.Builder builder) {
        this.tokens = tokens;
        this.http = builder.build();
    }

    @Override
    public boolean isEnabled() {
        return tokens.isConfigured();
    }

    @Override
    public String put(String sharedDriveId, String rootFolderId, List<String> folderSegments,
                      String name, String contentType, byte[] bytes, UUID documentId) {
        String token = tokens.accessToken(WRITE_SCOPE);
        // The connection stores a plain folder id — which may be a Shared Drive root, a folder INSIDE a
        // Shared Drive, or a My Drive folder. Resolve the folder's actual driveId (null = My Drive) rather
        // than assuming the folder itself is the drive (which produced "Shared drive not found").
        String driveId = resolveDriveId(token, rootFolderId);
        String parent = rootFolderId;
        for (String segment : folderSegments) {
            parent = ensureFolder(token, driveId, parent, segment);
        }
        return createFile(token, parent, name, contentType, bytes, documentId);
    }

    /** The Shared Drive id a folder lives in, or {@code null} when it is a personal My Drive folder. */
    private String resolveDriveId(String token, String folderId) {
        JsonNode node = getJson(http.get()
                .uri("https://www.googleapis.com/drive/v3/files/{id}?fields=driveId&supportsAllDrives=true", folderId)
                .header("Authorization", "Bearer " + token)
                .retrieve().body(String.class));
        String driveId = node.path("driveId").asText(null);
        return (driveId == null || driveId.isBlank()) ? null : driveId;
    }

    @Override
    public void delete(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            return;
        }
        try {
            http.delete()
                    .uri("https://www.googleapis.com/drive/v3/files/{id}?supportsAllDrives=true", fileId)
                    .header("Authorization", "Bearer " + tokens.accessToken(WRITE_SCOPE))
                    .retrieve().toBodilessEntity();
        } catch (RuntimeException e) {
            log.warn("Drive mirror delete failed for file {}", fileId, e);  // best-effort
        }
    }

    /** Find a child folder by name under {@code parentId}; create it if absent. Returns its id.
     *  {@code driveId} null → search My Drive (no Shared-Drive scoping). */
    private String ensureFolder(String token, String driveId, String parentId, String name) {
        String q = "name = '" + name.replace("\\", "\\\\").replace("'", "\\'")
                + "' and mimeType = '" + FOLDER_MIME + "' and '" + parentId + "' in parents and trashed = false";
        JsonNode found = getJson(http.get()
                .uri(b -> {
                    b.scheme("https").host("www.googleapis.com").path("/drive/v3/files")
                            .queryParam("q", q)
                            .queryParam("fields", "files(id,name)")
                            .queryParam("pageSize", "1")
                            .queryParam("supportsAllDrives", "true")
                            .queryParam("includeItemsFromAllDrives", "true");
                    if (driveId != null) {
                        b.queryParam("corpora", "drive").queryParam("driveId", driveId);
                    }
                    return b.build();
                })
                .header("Authorization", "Bearer " + token)
                .retrieve().body(String.class));
        JsonNode files = found.path("files");
        if (files.isArray() && files.size() > 0) {
            return files.get(0).path("id").asText();
        }
        // Create the folder.
        String metadata = "{\"name\":" + jstr(name) + ",\"mimeType\":\"" + FOLDER_MIME
                + "\",\"parents\":[" + jstr(parentId) + "]}";
        JsonNode created = getJson(http.post()
                .uri("https://www.googleapis.com/drive/v3/files?supportsAllDrives=true&fields=id")
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json; charset=UTF-8")
                .body(metadata)
                .retrieve().body(String.class));
        return created.path("id").asText();
    }

    /** Multipart (metadata + media) create under {@code parentId}. Returns the new file id. */
    private String createFile(String token, String parentId, String name,
                              String contentType, byte[] bytes, UUID documentId) {
        String boundary = "myfinance-" + documentId;
        String ct = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
        String metadata = "{\"name\":" + jstr(name) + ",\"parents\":[" + jstr(parentId)
                + "],\"appProperties\":{\"myfinanceDocId\":" + jstr(documentId.toString()) + "}}";

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeUtf8(body, "--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n");
        writeUtf8(body, metadata);
        writeUtf8(body, "\r\n--" + boundary + "\r\nContent-Type: " + ct + "\r\n\r\n");
        try {
            body.write(bytes);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        writeUtf8(body, "\r\n--" + boundary + "--");

        JsonNode created = getJson(http.post()
                .uri("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&supportsAllDrives=true&fields=id")
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/related; boundary=" + boundary)
                .body(body.toByteArray())
                .retrieve().body(String.class));
        return created.path("id").asText();
    }

    private JsonNode getJson(String body) {
        try {
            return json.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("Drive response parse failed", e);
        }
    }

    private static void writeUtf8(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.write(b, 0, b.length);
    }

    /** Minimal JSON string escaping for names/ids we place into request bodies. */
    private static String jstr(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> b.append(c);
            }
        }
        return b.append('"').toString();
    }
}
