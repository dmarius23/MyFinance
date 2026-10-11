package ro.myfinance.ingestion.adapter.web;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.ingestion.application.GmailWebhookService;

/** Google Pub/Sub push endpoint. Authentication is the Google OIDC push JWT. */
@RestController
@RequestMapping("/api/v1/ingestion/gmail")
public class GmailWebhookController {

    private final GmailWebhookService webhook;

    public GmailWebhookController(GmailWebhookService webhook) {
        this.webhook = webhook;
    }

    @PostMapping("/pubsub")
    public ResponseEntity<Void> pubSub(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody JsonNode envelope) {
        webhook.handle(authorization, envelope);
        return ResponseEntity.noContent().build();
    }
}
