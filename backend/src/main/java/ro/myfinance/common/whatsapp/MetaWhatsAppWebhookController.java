package ro.myfinance.common.whatsapp;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public Meta callback endpoint; authenticity is enforced by the webhook service. */
@RestController
@RequestMapping("/api/v1/webhooks/whatsapp/meta")
public class MetaWhatsAppWebhookController {

    private final MetaWhatsAppWebhookService webhook;

    public MetaWhatsAppWebhookController(MetaWhatsAppWebhookService webhook) {
        this.webhook = webhook;
    }

    @GetMapping
    public ResponseEntity<String> verify(@RequestParam("hub.mode") String mode,
                                         @RequestParam("hub.verify_token") String token,
                                         @RequestParam("hub.challenge") String challenge) {
        return webhook.verifySubscription(mode, token, challenge)
                ? ResponseEntity.ok()
                        .contentType(MediaType.TEXT_PLAIN)
                        .body(challenge)
                : ResponseEntity.status(403).build();
    }

    @PostMapping
    public ResponseEntity<Void> receive(
            @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody String body) {
        webhook.handle(signature, body);
        return ResponseEntity.noContent().build();
    }
}
