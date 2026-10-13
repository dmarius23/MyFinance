package ro.myfinance.ingestion.adapter.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ro.myfinance.ingestion.application.CloudFolderConnector;
import ro.myfinance.ingestion.domain.SourceConnection;

/**
 * Google Drive adapter. Authenticates with a service account (key in env, see
 * {@link GoogleIngestionProperties}); the admin shares the target folder with the SA and configures its
 * folder id. Lists the folder subtree (carrying the relative path for company/period mapping) and
 * downloads file bytes via the Drive v3 REST API. Read-only scope.
 */
@Component
public class GoogleDriveFolderConnector implements CloudFolderConnector {

    private static final String SCOPE = "https://www.googleapis.com/auth/drive.readonly";
    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";
    private static final int MAX_DEPTH = 6;

    private final GoogleDriveTokens tokens;
    private final RestClient http;
    private final ObjectMapper json = new ObjectMapper();

    public GoogleDriveFolderConnector(GoogleDriveTokens tokens, RestClient.Builder builder) {
        this.tokens = tokens;
        this.http = builder.build();
    }

    @Override
    public String provider() {
        return "GOOGLE_DRIVE";
    }

    @Override
    public Listing list(SourceConnection connection, String cursor) {
        return list(connection, connection.getRootFolderId(), cursor);
    }

    @Override
    public List<Folder> subfolders(SourceConnection connection, String parentId) {
        String token = accessToken();
        List<Folder> out = new ArrayList<>();
        String pageToken = null;
        do {
            JsonNode page = listPage(token, parentId, pageToken);
            for (JsonNode f : page.path("files")) {
                if (FOLDER_MIME.equals(f.path("mimeType").asText(""))) {
                    out.add(new Folder(f.path("id").asText(), f.path("name").asText("")));
                }
            }
            pageToken = page.path("nextPageToken").asText(null);
        } while (pageToken != null && !pageToken.isBlank());
        return out;
    }

    @Override
    public Listing list(SourceConnection connection, String startFolderId, String cursor) {
        String token = accessToken();
        List<RemoteFile> out = new ArrayList<>();
        Deque<String[]> stack = new ArrayDeque<>(); // [folderId, relativePath, depth]
        stack.push(new String[]{startFolderId, "", "0"});
        while (!stack.isEmpty()) {
            String[] cur = stack.pop();
            String folderId = cur[0], path = cur[1];
            int depth = Integer.parseInt(cur[2]);
            String pageToken = null;
            do {
                JsonNode page = listPage(token, folderId, pageToken);
                for (JsonNode f : page.path("files")) {
                    String mime = f.path("mimeType").asText("");
                    String name = f.path("name").asText("");
                    if (FOLDER_MIME.equals(mime)) {
                        if (depth < MAX_DEPTH) {
                            String childPath = path.isEmpty() ? name : path + "/" + name;
                            stack.push(new String[]{f.path("id").asText(), childPath, String.valueOf(depth + 1)});
                        }
                    } else {
                        out.add(new RemoteFile(
                                f.path("id").asText(), name, path, mime,
                                f.path("size").asLong(0),
                                f.path("md5Checksum").asText(f.path("modifiedTime").asText("")),
                                parseTime(f.path("modifiedTime").asText(null))));
                    }
                }
                pageToken = page.path("nextPageToken").asText(null);
            } while (pageToken != null && !pageToken.isBlank());
        }
        // Full re-list each run; idempotency is handled by the ledger. No incremental cursor yet.
        return new Listing(out, null);
    }

    @Override
    public byte[] download(SourceConnection connection, RemoteFile file) {
        return http.get()
                .uri("https://www.googleapis.com/drive/v3/files/{id}?alt=media&supportsAllDrives=true", file.id())
                .header("Authorization", "Bearer " + accessToken())
                .retrieve()
                .body(byte[].class);
    }

    private JsonNode listPage(String token, String folderId, String pageToken) {
        // Build via the URI builder so each query param is encoded exactly once (passing a pre-encoded
        // string to uri(String) would double-encode it → Google "Invalid Value" on q).
        String body = http.get()
                .uri(b -> b.scheme("https").host("www.googleapis.com").path("/drive/v3/files")
                        .queryParam("q", "'" + folderId + "' in parents and trashed=false")
                        .queryParam("fields", "nextPageToken,files(id,name,mimeType,size,modifiedTime,md5Checksum)")
                        .queryParam("pageSize", "1000")
                        .queryParam("supportsAllDrives", "true")
                        .queryParam("includeItemsFromAllDrives", "true")
                        .queryParamIfPresent("pageToken",
                                java.util.Optional.ofNullable(pageToken).filter(s -> !s.isBlank()))
                        .build())
                .header("Authorization", "Bearer " + token)
                .retrieve().body(String.class);
        try {
            return json.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("Drive list parse failed", e);
        }
    }

    /** A valid access token, reusing the cached one until ~5 min before it expires. */
    private String accessToken() {
        if (!tokens.isConfigured()) {
            throw new IllegalStateException("Google Drive ingestion is not configured (service-account key missing)");
        }
        return tokens.accessToken(SCOPE);
    }

    private static Instant parseTime(String s) {
        try {
            return s == null ? null : Instant.parse(s);
        } catch (Exception e) {
            return null;
        }
    }
}
