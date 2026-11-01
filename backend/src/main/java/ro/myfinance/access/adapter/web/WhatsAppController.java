package ro.myfinance.access.adapter.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.access.application.WhatsAppDispatchService;
import ro.myfinance.common.email.EmailKind;
import ro.myfinance.common.email.EmailStatus;
import ro.myfinance.common.whatsapp.WhatsAppMessage;

/**
 * One generic WhatsApp endpoint shared by every module (tax, payroll, reports, document reminders): the
 * caller passes the module {@link EmailKind} + period + composed body. Firm staff only.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/whatsapp")
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'EMPLOYEE')")
public class WhatsAppController {

    private final WhatsAppDispatchService whatsapp;

    public WhatsAppController(WhatsAppDispatchService whatsapp) {
        this.whatsapp = whatsapp;
    }

    /** Send a WhatsApp message for a module + company + period. */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public WhatsAppView send(@PathVariable UUID companyId, @Valid @RequestBody SendRequest req,
                             @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return WhatsAppView.from(whatsapp.dispatch(req.kind(), companyId, req.period(),
                req.body(), null, idempotencyKey, req.language(),
                req.templateVariables() == null ? Map.of() : req.templateVariables(),
                req.attachmentDocumentIds() == null ? List.of() : req.attachmentDocumentIds(), null));
    }

    /** WhatsApp send history for a module + company + period (newest first). */
    @GetMapping
    public List<WhatsAppView> history(@PathVariable UUID companyId, @RequestParam EmailKind kind,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        return whatsapp.history(kind, companyId, period).stream().map(WhatsAppView::from).toList();
    }

    /** The resolved recipient phone (the company's representative) — to pre-fill the compose form. */
    @GetMapping("/recipient")
    public RecipientView recipient(@PathVariable UUID companyId) {
        return new RecipientView(whatsapp.resolveRecipient(companyId));
    }

    public record SendRequest(@NotNull EmailKind kind, @NotNull LocalDate period,
                              @Pattern(regexp = "[a-z]{2}_[A-Z]{2}") String language,
                              Map<String, String> templateVariables,
                              List<UUID> attachmentDocumentIds,
                              @NotBlank @Size(max = 10_000) String body) {
    }

    public record RecipientView(String phone) {
    }


    public record WhatsAppView(UUID id, String recipient, EmailStatus status, Instant sentAt, String body,
                               String providerStatus, Instant providerStatusAt) {
        static WhatsAppView from(WhatsAppMessage e) {
            return new WhatsAppView(e.getId(), e.getRecipient(), e.getStatus(), e.getSentAt(), e.getBody(),
                    e.getProviderStatus(), e.getProviderStatusAt());
        }
    }
}
