package ro.myfinance.ingestion.adapter.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
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

    private final GoogleIngestionProperties props;
    private final RestClient http;
    private final ObjectMapper json = new ObjectMapper();

    public GoogleDriveFolderConnector(GoogleIngestionProperties props, RestClient.Builder builder) {
        this.props = props;
        this.http = builder.build();
    }

    @Override
    public String provider() {
        return "GOOGLE_DRIVE";
    }

    @Override
    public Listing list(SourceConnection connection, String cursor) {
        String token = accessToken();
        List<RemoteFile> out = new ArrayList<>();
        Deque<String[]> stack = new ArrayDeque<>(); // [folderId, relativePath, depth]
        stack.push(new String[]{connection.getRootFolderId(), "", "0"});
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
        String uri = "https://www.googleapis.com/drive/v3/files"
                + "?q=" + enc("'" + folderId + "' in parents and trashed=false")
                + "&fields=" + enc("nextPageToken,files(id,name,mimeType,size,modifiedTime,md5Checksum)")
                + "&pageSize=1000&supportsAllDrives=true&includeItemsFromAllDrives=true"
                + (pageToken != null ? "&pageToken=" + enc(pageToken) : "");
        String body = http.get().uri(uri).header("Authorization", "Bearer " + token)
                .retrieve().body(String.class);
        try {
            return json.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("Drive list parse failed", e);
        }
    }

    /** Mint a short-lived access token from the service-account key (JWT-bearer grant). */
    private String accessToken() {
        if (!props.isConfigured()) {
            throw new IllegalStateException("Google Drive ingestion is not configured (service-account key missing)");
        }
        long now = Instant.now().getEpochSecond();
        String header = b64(json("{\"alg\":\"RS256\",\"typ\":\"JWT\"}"));
        String claims = b64(json("{\"iss\":\"" + props.clientEmail() + "\",\"scope\":\"" + SCOPE
                + "\",\"aud\":\"https://oauth2.googleapis.com/token\",\"iat\":" + now + ",\"exp\":" + (now + 3600) + "}"));
        String signingInput = header + "." + claims;
        String jwt = signingInput + "." + b64(sign(signingInput.getBytes(StandardCharsets.UTF_8)));

        String resp = http.post().uri("https://oauth2.googleapis.com/token")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body("grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=" + jwt)
                .retrieve().body(String.class);
        try {
            return json.readTree(resp).path("access_token").asText();
        } catch (Exception e) {
            throw new IllegalStateException("Google token exchange failed", e);
        }
    }

    private byte[] sign(byte[] data) {
        try {
            String pem = props.privateKey().replace("\\n", "\n")
                    .replaceAll("-----BEGIN (.*)-----", "").replaceAll("-----END (.*)-----", "")
                    .replaceAll("\\s", "");
            var key = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initSign(key);
            s.update(data);
            return s.sign();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign Google JWT", e);
        }
    }

    private static byte[] json(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static Instant parseTime(String s) {
        try {
            return s == null ? null : Instant.parse(s);
        } catch (Exception e) {
            return null;
        }
    }
}
