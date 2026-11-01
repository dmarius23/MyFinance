package ro.myfinance.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.myfinance.access.application.WhatsAppDispatchService;
import ro.myfinance.common.email.EmailKind;
import ro.myfinance.common.email.EmailStatus;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.common.whatsapp.WhatsAppMessage;
import ro.myfinance.common.whatsapp.WhatsAppMessageRepository;
import ro.myfinance.support.AbstractPostgresIT;

/**
 * Proves the WhatsApp channel end-to-end against the stub sender: dispatch resolves the representative's
 * phone, records a WHATSAPP history row, and the inline outbox relay flips it to SENT. Also proves the
 * dedicated message store is tenant-isolated (RLS).
 */
class WhatsAppDispatchIT extends AbstractPostgresIT {

    private static final LocalDate PERIOD = LocalDate.of(2026, 4, 1);

    @Autowired WhatsAppDispatchService whatsapp;
    @Autowired WhatsAppMessageRepository messages;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private UUID seedCompanyWithRep(UUID tenant, String phone) {
        jdbc.update("insert into tenant(id, name, status, plan) values (?, 't', 'ACTIVE', 'STD') on conflict do nothing", tenant);
        UUID company = UUID.randomUUID();
        jdbc.update("insert into company(id, tenant_id, legal_name, cui, status) values (?, ?, 'Acme SRL', ?, 'ACTIVE')",
                company, tenant, "RO-" + company);
        UUID rep = UUID.randomUUID();
        jdbc.update("insert into app_user(id, tenant_id, email, name, phone, role, status) values (?,?,?,?,?, 'REPRESENTATIVE','ACTIVE')",
                rep, tenant, "rep+" + rep + "@client.ro", "Rep", phone);
        jdbc.update("insert into representative_link(id, tenant_id, user_id, company_id) values (?,?,?,?)",
                UUID.randomUUID(), tenant, rep, company);
        return company;
    }

    @Test
    void dispatchResolvesPhoneRecordsAndRelaysToSent() {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(new TenantContext.Identity(tenant, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        UUID company = seedCompanyWithRep(tenant, "+40711223344");

        WhatsAppMessage sent = whatsapp.dispatch(EmailKind.TAX, company, PERIOD, "Aveți de plată 1.000 RON.",
                null, "tax-" + company, null);
        assertThat(sent.getRecipient()).isEqualTo("+40711223344"); // resolved from the representative

        List<WhatsAppMessage> waLog = whatsapp.history(EmailKind.TAX, company, PERIOD);
        assertThat(waLog).hasSize(1);
        assertThat(waLog.get(0).getStatus()).isEqualTo(EmailStatus.SENT); // inline outbox relay delivered it

        assertThat(messages.findByKindAndCompanyIdAndPeriodMonthOrderBySentAtDesc(
                EmailKind.TAX, company, PERIOD)).hasSize(1);
    }

    @Test
    void whatsappHistoryIsTenantIsolated() {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(new TenantContext.Identity(tenant, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        UUID company = seedCompanyWithRep(tenant, "+40700000000");
        whatsapp.dispatch(EmailKind.PAYROLL, company, PERIOD, "Statele sunt gata.", null,
                "payroll-" + company, null);

        TenantContext.set(new TenantContext.Identity(UUID.randomUUID(), UUID.randomUUID(), Role.TENANT_ADMIN, null));
        assertThat(whatsapp.history(EmailKind.PAYROLL, company, PERIOD)).isEmpty();
    }

    @Test
    void crossTenantCompanyCannotBeSentTo() {
        UUID owner = UUID.randomUUID();
        TenantContext.set(new TenantContext.Identity(owner, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        UUID company = seedCompanyWithRep(owner, "+40700000000");

        TenantContext.set(new TenantContext.Identity(UUID.randomUUID(), UUID.randomUUID(), Role.TENANT_ADMIN, null));
        assertThatThrownBy(() -> whatsapp.dispatch(EmailKind.TAX, company, PERIOD, "Nu trimite.",
                null, "cross-tenant-" + company, null))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void metaSendFallsBackToEnglishTemplateWhenNoRomanianIsApproved() {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(new TenantContext.Identity(tenant, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        UUID company = seedCompanyWithRep(tenant, "+40700000000");
        jdbc.update("""
                insert into tenant_whatsapp_provider(tenant_id, mode, meta_waba_id, meta_phone_number_id,
                    meta_access_token_enc, updated_at)
                values (?, 'META_CLOUD_API', 'waba', 'phone', 'token', now())
                """, tenant);
        jdbc.update("""
                insert into tenant_whatsapp_template(tenant_id, kind, language, meta_template_name, status)
                values (?, 'DOCUMENT_REMINDER', 'en_US', 'hello_world', 'APPROVED'),
                       (?, 'DOCUMENT_REMINDER', 'ro_RO', 'pending_ro', 'PENDING')
                """, tenant, tenant);

        WhatsAppMessage sent = whatsapp.dispatch(EmailKind.DOCUMENT_REMINDER, company, PERIOD,
                "Documente lipsă.", null, "reminder-" + company, null);

        assertThat(jdbc.queryForObject(
                "select template_name || '/' || template_language from whatsapp_message where id = ?",
                String.class, sent.getId())).isEqualTo("hello_world/en_US");
    }

    @Test
    void failedProviderDeliveryIsRecordedForRetryInsteadOfRolledBack() {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(new TenantContext.Identity(tenant, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        UUID company = seedCompanyWithRep(tenant, "+40700000000");
        jdbc.update("""
                insert into tenant_whatsapp_provider(tenant_id, mode, meta_waba_id, meta_phone_number_id,
                    meta_access_token_enc, updated_at)
                values (?, 'META_CLOUD_API', 'waba', 'phone', 'not-a-ciphertext', now())
                """, tenant);
        jdbc.update("""
                insert into tenant_whatsapp_template(tenant_id, kind, language, meta_template_name, status)
                values (?, 'TAX', 'ro_RO', 'tax_notice', 'APPROVED')
                """, tenant);

        WhatsAppMessage sent = whatsapp.dispatch(EmailKind.TAX, company, PERIOD, "Taxe.", null,
                "tax-fail-" + company, null);

        var outbox = jdbc.queryForMap(
                "select status, attempts, error from outbox_message where aggregate_id = ?",
                sent.getId().toString());
        assertThat(outbox.get("status")).isEqualTo("PENDING");
        assertThat(outbox.get("attempts")).isEqualTo(1);
        assertThat(outbox.get("error")).isNotNull();
    }

    @Test
    void repeatedIdempotencyKeyDoesNotCreateAnotherMessage() {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(new TenantContext.Identity(tenant, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        UUID company = seedCompanyWithRep(tenant, "+40700000000");

        WhatsAppMessage first = whatsapp.dispatch(EmailKind.REPORT, company, PERIOD, "Raport disponibil.",
                null, "same-key", null);
        WhatsAppMessage second = whatsapp.dispatch(EmailKind.REPORT, company, PERIOD, "Raport disponibil.",
                null, "same-key", null);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(whatsapp.history(EmailKind.REPORT, company, PERIOD)).hasSize(1);
    }
}
