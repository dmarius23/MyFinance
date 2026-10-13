package ro.myfinance.ingestion.adapter.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.UserCredentials;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import ro.myfinance.common.crypto.SecretCipher;
import ro.myfinance.ingestion.application.GmailMailboxClient;
import ro.myfinance.ingestion.application.GmailAuthorizationRevokedException;
import ro.myfinance.ingestion.application.GmailTransientException;
import ro.myfinance.ingestion.domain.EmailConnection;

/** Gmail REST adapter: token refresh, mailbox watch, history changes and attachment downloads. */
@Component
public class GmailApiMailboxClient implements GmailMailboxClient {

    private static final String GMAIL_API = "https://gmail.googleapis.com/gmail/v1";
    private static final DateTimeFormatter GMAIL_DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final GmailOAuthProperties properties;
    private final SecretCipher cipher;
    private final RestClient http;
    private final ObjectMapper json;

    public GmailApiMailboxClient(GmailOAuthProperties properties, SecretCipher cipher,
                                 RestClient.Builder builder, ObjectMapper json) {
        this.properties = properties;
        this.cipher = cipher;
        this.http = builder.build();
        this.json = json;
    }

    @Override
    public WatchRegistration watch(EmailConnection connection) {
        requireConfigured(connection);
        if (properties.pubSubTopic() == null || properties.pubSubTopic().isBlank()) {
            throw new IllegalStateException("Gmail Pub/Sub topic is not configured");
        }
        JsonNode response;
        try {
            response = http.post()
                    .uri(GMAIL_API + "/users/me/watch")
                    .header("Authorization", "Bearer " + accessToken(connection))
                    .contentType(MediaType.APPLICATION_JSON)
                    // Do not restrict notifications to INBOX: user filters may archive or relabel a
                    // message immediately, which could otherwise hide it from the ingestion cursor.
                    .body(Map.of("topicName", properties.pubSubTopic()))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw classify("Gmail watch request failed", e);
        }
        return new WatchRegistration(response.path("historyId").asText(),
                Instant.ofEpochMilli(response.path("expiration").asLong()));
    }

    @Override
    public void revoke(EmailConnection connection) {
        requireConfigured(connection);
        String refreshToken = cipher.decrypt(connection.getRefreshTokenEncrypted());
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        try {
            LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("token", refreshToken);
            http.post()
                    .uri("https://oauth2.googleapis.com/revoke")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            // A grant already revoked by the user is equivalent to a successful disconnect.
            if (e.getStatusCode().value() != 400 && e.getStatusCode().value() != 401) {
                throw e;
            }
        }
    }

    @Override
    public List<MessageRef> changes(EmailConnection connection, String startHistoryId) {
        requireConfigured(connection);
        if (startHistoryId == null || startHistoryId.isBlank()) {
            return List.of();
        }

        String pageToken = null;
        Map<String, MessageRef> messages = new LinkedHashMap<>();
        do {
            UriComponentsBuilder uri = UriComponentsBuilder
                    .fromUriString(GMAIL_API + "/users/me/history")
                    .queryParam("startHistoryId", startHistoryId)
                    .queryParam("historyTypes", "messageAdded");
            if (pageToken != null) {
                uri.queryParam("pageToken", pageToken);
            }
            JsonNode page;
            try {
                page = get(uri.toUriString(), connection);
            } catch (HttpClientErrorException.NotFound e) {
                throw new ro.myfinance.ingestion.application.GmailHistoryExpiredException(
                        "Gmail history cursor expired", e);
            }
            for (JsonNode history : page.path("history")) {
                for (JsonNode added : history.path("messagesAdded")) {
                    String messageId = added.path("message").path("id").asText(null);
                    if (messageId != null) {
                        messages.putIfAbsent(messageId, fetchMessage(connection, messageId));
                    }
                }
            }
            pageToken = page.path("nextPageToken").asText(null);
        } while (pageToken != null && !pageToken.isBlank());
        return new ArrayList<>(messages.values());
    }

    @Override
    public List<MessageRef> backfill(EmailConnection connection, LocalDate fromInclusive,
                                     LocalDate toExclusive) {
        requireConfigured(connection);
        if (fromInclusive == null || toExclusive == null || !fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("Backfill interval must be non-empty");
        }
        String query = "after:" + GMAIL_DATE.format(fromInclusive)
                + " before:" + GMAIL_DATE.format(toExclusive) + " has:attachment";
        String pageToken = null;
        Map<String, MessageRef> messages = new LinkedHashMap<>();
        do {
            UriComponentsBuilder uri = UriComponentsBuilder
                    .fromUriString(GMAIL_API + "/users/me/messages")
                    .queryParam("q", query)
                    .queryParam("includeSpamTrash", false);
            if (pageToken != null) {
                uri.queryParam("pageToken", pageToken);
            }
            JsonNode page = get(uri.toUriString(), connection);
            for (JsonNode item : page.path("messages")) {
                String messageId = item.path("id").asText(null);
                if (messageId != null) {
                    messages.putIfAbsent(messageId, fetchMessage(connection, messageId));
                }
            }
            pageToken = page.path("nextPageToken").asText(null);
        } while (pageToken != null && !pageToken.isBlank());
        return new ArrayList<>(messages.values());
    }

    @Override
    public String currentHistoryId(EmailConnection connection) {
        requireConfigured(connection);
        JsonNode response = get(GMAIL_API + "/users/me/profile", connection);
        return response.path("historyId").asText(null);
    }

    @Override
    public byte[] attachment(EmailConnection connection, MessageRef message, AttachmentRef attachment) {
        requireConfigured(connection);
        if (attachment.inlineData() != null && !attachment.inlineData().isBlank()) {
            return decode(attachment.inlineData());
        }
        String url = GMAIL_API + "/users/me/messages/" + encode(message.gmailMessageId())
                + "/attachments/" + encode(attachment.gmailAttachmentId());
        JsonNode response = get(url, connection);
        return decode(response.path("data").asText(""));
    }

    private MessageRef fetchMessage(EmailConnection connection, String messageId) {
        String url = UriComponentsBuilder
                .fromUriString(GMAIL_API + "/users/me/messages/" + encode(messageId))
                .queryParam("format", "full")
                .toUriString();
        JsonNode message = get(url, connection);
        JsonNode payload = message.path("payload");
        Map<String, String> headers = headers(payload.path("headers"));
        List<AttachmentRef> attachments = new ArrayList<>();
        collectAttachments(payload, attachments);
        long internalDate = message.path("internalDate").asLong(0);
        return new MessageRef(messageId, message.path("threadId").asText(null),
                sender(headers.get("From")), headers.get("Subject"),
                internalDate == 0 ? null : Instant.ofEpochMilli(internalDate), attachments);
    }

    private void collectAttachments(JsonNode part, List<AttachmentRef> result) {
        String filename = part.path("filename").asText("");
        JsonNode body = part.path("body");
        String attachmentId = body.path("attachmentId").asText(null);
        String inlineData = body.path("data").asText(null);
        if (!filename.isBlank() && (attachmentId != null || inlineData != null)) {
            result.add(new AttachmentRef(attachmentId == null ? "inline-" + result.size() : attachmentId,
                    filename, part.path("mimeType").asText(null), inlineData));
        }
        for (JsonNode child : part.path("parts")) {
            collectAttachments(child, result);
        }
    }

    private JsonNode get(String url, EmailConnection connection) {
        try {
            return http.get().uri(url)
                    .header("Authorization", "Bearer " + accessToken(connection))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw classify("Gmail request failed", e);
        }
    }

    private String accessToken(EmailConnection connection) {
        String refreshToken = cipher.decrypt(connection.getRefreshTokenEncrypted());
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new IllegalStateException("Gmail connection has no refresh token");
        }
        try {
            UserCredentials credentials = UserCredentials.newBuilder()
                    .setClientId(properties.clientId())
                    .setClientSecret(properties.clientSecret())
                    .setRefreshToken(refreshToken)
                    .build();
            AccessToken token = credentials.refreshAccessToken();
            if (token == null || token.getTokenValue() == null || token.getTokenValue().isBlank()) {
                throw new IllegalStateException("Google did not return a Gmail access token");
            }
            return token.getTokenValue();
        } catch (IOException e) {
            String detail = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (detail.contains("invalid_grant") || detail.contains("unauthorized_client")
                    || detail.contains("401") || detail.contains("400")) {
                throw new GmailAuthorizationRevokedException("Gmail authorization was rejected", e);
            }
            throw new GmailTransientException("Gmail token refresh failed", e);
        }
    }

    private static RuntimeException classify(String message, RestClientResponseException error) {
        int status = error.getStatusCode().value();
        if (status == 401 || status == 403) {
            return new GmailAuthorizationRevokedException(message, error);
        }
        if (status == 429 || status >= 500) {
            return new GmailTransientException(message, error);
        }
        return error;
    }

    private void requireConfigured(EmailConnection connection) {
        if (!properties.isConfigured() || !cipher.isConfigured()) {
            throw new IllegalStateException("Gmail OAuth is not configured");
        }
        if (connection.getRefreshTokenEncrypted() == null) {
            throw new IllegalStateException("Gmail connection is not authorized");
        }
    }

    private static Map<String, String> headers(JsonNode headers) {
        Map<String, String> result = new LinkedHashMap<>();
        for (JsonNode header : headers) {
            result.put(header.path("name").asText().toLowerCase(),
                    header.path("value").asText(""));
        }
        return result;
    }

    private static String sender(String from) {
        if (from == null || from.isBlank()) {
            return "";
        }
        int open = from.lastIndexOf('<');
        int close = from.lastIndexOf('>');
        return open >= 0 && close > open ? from.substring(open + 1, close).trim() : from.trim();
    }

    private static byte[] decode(String value) {
        if (value == null || value.isBlank()) {
            return new byte[0];
        }
        return Base64.getUrlDecoder().decode(value);
    }

    private static String encode(String value) {
        return UriComponentsBuilder.fromPath(value).build().encode().toUriString();
    }
}
