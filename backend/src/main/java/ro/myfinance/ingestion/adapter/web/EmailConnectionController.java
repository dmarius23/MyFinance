package ro.myfinance.ingestion.adapter.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.ingestion.application.EmailConnectionService;
import ro.myfinance.ingestion.application.GmailIngestionService;
import ro.myfinance.ingestion.application.GmailOAuthService;
import ro.myfinance.ingestion.application.GmailMailboxClient;
import ro.myfinance.ingestion.domain.EmailConnection;

/** Initial Gmail connection screen API. OAuth authorization is intentionally not accepted from the client. */
@RestController
@RequestMapping("/api/v1/ingestion/email-connections")
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class EmailConnectionController {

    private final EmailConnectionService connections;
    private final GmailOAuthService oauth;
    private final GmailIngestionService ingestion;
    private final GmailMailboxClient gmail;

    public EmailConnectionController(EmailConnectionService connections, GmailOAuthService oauth,
                                    GmailIngestionService ingestion, GmailMailboxClient gmail) {
        this.connections = connections;
        this.oauth = oauth;
        this.ingestion = ingestion;
        this.gmail = gmail;
    }

    @GetMapping
    public List<ConnectionView> list() {
        return connections.list().stream().map(ConnectionView::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConnectionView create(@Valid @RequestBody CreateRequest request) {
        return ConnectionView.from(connections.create(request.displayName(), request.mailboxAddress()));
    }

    @PostMapping("/oauth/start")
    public GmailOAuthService.AuthorizationStart startNewOAuth() {
        return oauth.start(connections.createPending().getId());
    }

    @PostMapping("/{id}/oauth/start")
    public GmailOAuthService.AuthorizationStart startOAuth(@PathVariable UUID id) {
        return oauth.start(id);
    }

    @PostMapping("/{id}/oauth/complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void completeOAuth(@PathVariable UUID id, @Valid @RequestBody CompleteOAuthRequest request) {
        oauth.complete(id, request.state(), request.authorizationCode());
    }

    @PostMapping("/{id}/sync")
    public GmailIngestionService.SyncResult sync(@PathVariable UUID id) {
        return ingestion.sync(id, null);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(@PathVariable UUID id) {
        connections.disconnect(id, gmail);
    }

    @PostMapping("/{id}/backfill")
    public GmailIngestionService.SyncResult backfill(@PathVariable UUID id,
                                                     @RequestBody(required = false) BackfillRequest request) {
        LocalDate to = request == null || request.toExclusive() == null
                ? LocalDate.now(ZoneOffset.UTC).plusDays(1) : request.toExclusive();
        LocalDate from = request == null || request.fromInclusive() == null
                ? to.minusDays(30) : request.fromInclusive();
        return ingestion.backfill(id, from, to);
    }

    public record CreateRequest(@NotBlank String displayName, @NotBlank @Email String mailboxAddress) {
    }

    public record CompleteOAuthRequest(@NotBlank String state, @NotBlank String authorizationCode) {
    }

    public record BackfillRequest(LocalDate fromInclusive, LocalDate toExclusive) {
    }

    public record ConnectionView(UUID id, String displayName, String mailboxAddress, String status,
                                 Instant watchExpiresAt, Instant lastSyncedAt, String lastResult) {
        static ConnectionView from(EmailConnection c) {
            return new ConnectionView(c.getId(), c.getDisplayName(), c.getMailboxAddress(),
                    c.getStatus(), c.getWatchExpiresAt(), c.getLastSyncedAt(), c.getLastResult());
        }
    }
}
