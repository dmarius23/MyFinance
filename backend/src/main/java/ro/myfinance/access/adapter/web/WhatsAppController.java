package ro.myfinance.access.adapter.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.access.application.WhatsAppDispatchService;
import ro.myfinance.common.email.EmailHistory;
import ro.myfinance.common.email.EmailKind;
import ro.myfinance.common.email.EmailStatus;

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
    public WhatsAppView send(@PathVariable UUID companyId, @RequestBody SendRequest req) {
        return WhatsAppView.from(whatsapp.dispatch(req.kind(), companyId, req.period(), req.recipient(),
                req.body(), null, null));
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

    public record SendRequest(EmailKind kind, LocalDate period, String recipient, String body) {
    }

    public record RecipientView(String phone) {
    }

    public record WhatsAppView(UUID id, String recipient, EmailStatus status, Instant sentAt, String body) {
        static WhatsAppView from(EmailHistory e) {
            return new WhatsAppView(e.getId(), e.getRecipient(), e.getStatus(), e.getSentAt(), e.getBody());
        }
    }
}
