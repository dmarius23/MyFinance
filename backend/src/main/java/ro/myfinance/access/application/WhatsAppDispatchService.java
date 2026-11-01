package ro.myfinance.access.application;

import java.time.LocalDate;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.company.adapter.persistence.CompanyRepository;
import ro.myfinance.common.email.EmailKind;
import ro.myfinance.common.email.EmailStatus;
import ro.myfinance.common.outbox.OutboxWriter;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.whatsapp.WhatsAppOutboxHandler;
import ro.myfinance.common.whatsapp.WhatsAppSender;
import ro.myfinance.common.whatsapp.TenantWhatsAppProvider;
import ro.myfinance.common.whatsapp.TenantWhatsAppProviderRepository;
import ro.myfinance.common.whatsapp.TenantWhatsAppTemplate;
import ro.myfinance.common.whatsapp.TenantWhatsAppTemplateRepository;
import ro.myfinance.common.whatsapp.WhatsAppMediaSource;
import ro.myfinance.common.whatsapp.WhatsAppMessage;
import ro.myfinance.common.whatsapp.WhatsAppMessageRepository;
import ro.myfinance.common.web.ConflictException;
import ro.myfinance.common.web.NotFoundException;

/**
 * The one place a client WhatsApp message is resolved, recorded and durably queued — the channel analogue
 * of {@link EmailDispatchService}. Given a {@link EmailKind} plus the composed body, it resolves the
 * recipient phone from the company's server-authorized contact, appends one {@link WhatsAppMessage} row
 * (status QUEUED) and writes a {@code SEND_WHATSAPP} outbox message in the same
 * transaction — so the send is committed atomically with the history and never lost on a crash. The
 * worker relay then delivers it (with retries) and flips the row to SENT, or FAILED on DLQ.
 */
@Service
@Transactional
public class WhatsAppDispatchService {

    private final EmailEnvelopeService envelopes;
    private final WhatsAppMessageRepository messages;
    private final OutboxWriter outbox;
    private final WhatsAppOutboxHandler whatsappOutbox;
    private final CompanyRepository companies;
    private final TenantWhatsAppProviderRepository providers;
    private final TenantWhatsAppTemplateRepository templates;
    private final WhatsAppMediaSource media;
    private final String defaultCountryCode;
    private final int monthlyQuota;
    private final boolean loggingFallbackEnabled;
    private final ObjectMapper json;

    public WhatsAppDispatchService(EmailEnvelopeService envelopes, WhatsAppMessageRepository messages,
                                   OutboxWriter outbox, WhatsAppOutboxHandler whatsappOutbox,
                                   CompanyRepository companies,
                                   TenantWhatsAppProviderRepository providers,
                                   TenantWhatsAppTemplateRepository templates,
                                   WhatsAppMediaSource media,
                                   @org.springframework.beans.factory.annotation.Value(
                                           "${myfinance.whatsapp.default-country-code:+40}") String defaultCountryCode,
                                   @org.springframework.beans.factory.annotation.Value(
                                           "${myfinance.whatsapp.monthly-quota:1000}") int monthlyQuota,
                                   @org.springframework.beans.factory.annotation.Value(
                                           "${myfinance.whatsapp.logging-fallback-enabled:false}")
                                   boolean loggingFallbackEnabled,
                                   ObjectMapper json) {
        this.envelopes = envelopes;
        this.messages = messages;
        this.outbox = outbox;
        this.whatsappOutbox = whatsappOutbox;
        this.companies = companies;
        this.providers = providers;
        this.templates = templates;
        this.media = media;
        this.defaultCountryCode = defaultCountryCode;
        this.monthlyQuota = monthlyQuota;
        this.loggingFallbackEnabled = loggingFallbackEnabled;
        this.json = json;
    }

    /**
     * Resolve → record (QUEUED) → enqueue one WhatsApp message. The recipient is always resolved
     * server-side; the browser cannot override it. Reusing an idempotency key returns the original row.
     */
    public WhatsAppMessage dispatch(EmailKind kind, UUID companyId, LocalDate period, String body,
                                 List<UUID> relatedIds, String idempotencyKey, Runnable onSent) {
        return dispatch(kind, companyId, period, body, relatedIds, idempotencyKey, "ro_RO", Map.of(),
                List.of(), onSent);
    }

    public WhatsAppMessage dispatch(EmailKind kind, UUID companyId, LocalDate period, String body,
                                 List<UUID> relatedIds, String idempotencyKey, String language,
                                 Runnable onSent) {
        return dispatch(kind, companyId, period, body, relatedIds, idempotencyKey, language, Map.of(),
                List.of(), onSent);
    }

    public WhatsAppMessage dispatch(EmailKind kind, UUID companyId, LocalDate period, String body,
                                 List<UUID> relatedIds, String idempotencyKey, String language,
                                 Map<String, String> templateVariables, List<UUID> attachmentIds,
                                 Runnable onSent) {
        UUID tenantId = TenantContext.tenantId().orElseThrow(() -> new IllegalStateException("No tenant bound"));
        UUID userId = TenantContext.current().map(TenantContext.Identity::userId).orElse(null);
        if (kind == null || companyId == null || period == null || body == null || body.isBlank()) {
            throw new IllegalArgumentException("kind, companyId, period and body are required");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        if (idempotencyKey.length() > 200) {
            throw new IllegalArgumentException("Idempotency-Key is too long");
        }
        companies.findById(companyId)
                .orElseThrow(() -> new NotFoundException("Company not found: " + companyId));
        LocalDate month = period.withDayOfMonth(1);

        String rawPhone = envelopes.representativePhone(companyId);
        String toPhone = ro.myfinance.common.whatsapp.PhoneNumbers.toE164(rawPhone, defaultCountryCode);
        if (toPhone == null || toPhone.isBlank()) {
            throw new ConflictException("The company has no eligible WhatsApp contact");
        }
        Map<String, String> variables = templateVariables == null ? Map.of() : templateVariables;
        List<UUID> attachments = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
        attachments.forEach(documentId -> media.describe(companyId, documentId));
        String fingerprint = fingerprint(kind, companyId, month, toPhone, body, language, variables);
        var existing = messages.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey);
        if (existing.isPresent()) {
            WhatsAppMessage prior = existing.get();
            if (!fingerprint.equals(prior.getIdempotencyHash())) {
                throw new ConflictException("Idempotency-Key was already used for a different message");
            }
            return prior;
        }
        TenantWhatsAppTemplate template = validateProviderAndTemplate(tenantId, kind, language);
        YearMonth currentMonth = YearMonth.now(ZoneOffset.UTC);
        Instant quotaStart = currentMonth.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant quotaEnd = currentMonth.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        long used = messages.countByTenantIdAndSentAtGreaterThanEqualAndSentAtLessThan(
                tenantId, quotaStart, quotaEnd);
        if (monthlyQuota <= 0 || used >= monthlyQuota) {
            throw new ConflictException("The tenant WhatsApp monthly quota has been reached");
        }

        WhatsAppMessage row = messages.save(new WhatsAppMessage(tenantId, kind, companyId, month,
                relatedIds == null ? List.of() : relatedIds, attachments, toPhone, body, EmailStatus.QUEUED,
                userId, idempotencyKey, fingerprint, template == null ? null : template.getMetaTemplateName(),
                template == null ? null : template.getLanguage(), serializeVariables(variables)));

        var message = new WhatsAppSender.Message(envelopes.currentUserName(), toPhone, body,
                template == null ? null : template.getMetaTemplateName(),
                template == null ? null : template.getLanguage(), variables, companyId,
                attachments.stream().map(WhatsAppSender.Attachment::new).toList());
        outbox.enqueue(WhatsAppOutboxHandler.AGGREGATE, row.getId().toString(), WhatsAppOutboxHandler.TYPE,
                whatsappOutbox.serialize(new WhatsAppOutboxHandler.Payload(row.getId(), message)));

        if (onSent != null) {
            onSent.run();
        }
        return row;
    }

    /** WhatsApp send history for one company/period + kind (newest first) — the log modal. */
    @Transactional(readOnly = true)
    public List<WhatsAppMessage> history(EmailKind kind, UUID companyId, LocalDate period) {
        return messages.findByKindAndCompanyIdAndPeriodMonthOrderBySentAtDesc(
                kind, companyId, period.withDayOfMonth(1));
    }

    /** The phone WhatsApp would send to for this company (the representative's), for pre-filling the UI. */
    @Transactional(readOnly = true)
    public String resolveRecipient(UUID companyId) {
        requireCompany(companyId);
        return envelopes.representativePhone(companyId);
    }

    private void requireCompany(UUID companyId) {
        companies.findById(companyId)
                .orElseThrow(() -> new NotFoundException("Company not found: " + companyId));
    }

    private java.util.Optional<TenantWhatsAppTemplate> approvedTemplate(EmailKind kind, String language) {
        return templates.findByKindAndLanguage(kind, language)
                .filter(t -> t.getStatus() == TenantWhatsAppTemplate.Status.APPROVED);
    }

    private TenantWhatsAppTemplate validateProviderAndTemplate(UUID tenantId, EmailKind kind, String language) {
        TenantWhatsAppProvider provider = providers.findById(tenantId).orElse(null);
        if (provider == null) {
            if (loggingFallbackEnabled) {
                return null;
            }
            throw new ConflictException("Configure a WhatsApp provider before sending");
        }
        if (provider.getMode() == TenantWhatsAppProvider.Mode.OFF
                || provider.getMode() == TenantWhatsAppProvider.Mode.CLICK_TO_CHAT) {
            throw new ConflictException("Configure an API WhatsApp provider before sending");
        }
        if (provider.getMode() == TenantWhatsAppProvider.Mode.META_CLOUD_API) {
            if (!provider.isSendable()) {
                throw new ConflictException("Complete Meta WhatsApp onboarding before sending");
            }
            String locale = language == null || language.isBlank() ? "ro_RO" : language;
            String fallback = "ro_RO".equals(locale) ? "en_US" : "ro_RO";
            TenantWhatsAppTemplate template = approvedTemplate(kind, locale)
                    .or(() -> approvedTemplate(kind, fallback))
                    .orElseThrow(() -> new ConflictException(
                            "No approved Meta WhatsApp template is configured for " + kind + "/" + locale));
            if (template.getMetaTemplateName().isBlank()) {
                throw new ConflictException("The configured WhatsApp template has no Meta template name");
            }
            return template;
        }
        return null;
    }

    private static String fingerprint(EmailKind kind, UUID companyId, LocalDate month,
                                      String recipient, String body, String language,
                                      Map<String, String> variables) {
        String value = kind.name() + "|" + companyId + "|" + month + "|" + recipient + "|" + body
                + "|" + language + "|" + variables;
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private String serializeVariables(Map<String, String> variables) {
        try {
            return json.writeValueAsString(variables);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid WhatsApp template variables", e);
        }
    }
}
