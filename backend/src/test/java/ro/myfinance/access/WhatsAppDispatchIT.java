package ro.myfinance.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.myfinance.access.application.WhatsAppDispatchService;
import ro.myfinance.common.email.EmailHistory;
import ro.myfinance.common.email.EmailHistoryRepository;
import ro.myfinance.common.email.EmailKind;
import ro.myfinance.common.email.EmailStatus;
import ro.myfinance.common.email.MessageChannel;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.support.AbstractPostgresIT;

/**
 * Proves the WhatsApp channel end-to-end against the stub sender: dispatch resolves the representative's
 * phone, records a WHATSAPP history row, and the inline outbox relay flips it to SENT. Also proves the
 * channel is isolated (the row is invisible to EMAIL history) and tenant-isolated (RLS).
 */
class WhatsAppDispatchIT extends AbstractPostgresIT {

    private static final LocalDate PERIOD = LocalDate.of(2026, 4, 1);

    @Autowired WhatsAppDispatchService whatsapp;
    @Autowired EmailHistoryRepository history;
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

        EmailHistory sent = whatsapp.dispatch(EmailKind.TAX, company, PERIOD, null, "Aveți de plată 1.000 RON.", null, null);
        assertThat(sent.getRecipient()).isEqualTo("+40711223344"); // resolved from the representative
        assertThat(sent.getChannel()).isEqualTo(MessageChannel.WHATSAPP);

        List<EmailHistory> waLog = whatsapp.history(EmailKind.TAX, company, PERIOD);
        assertThat(waLog).hasSize(1);
        assertThat(waLog.get(0).getStatus()).isEqualTo(EmailStatus.SENT); // inline outbox relay delivered it

        // Channel isolation: the WhatsApp row must NOT show up in EMAIL history for the same kind/period.
        assertThat(history.findByKindAndCompanyIdAndPeriodMonthOrderBySentAtDesc(EmailKind.TAX, company, PERIOD)).isEmpty();
    }

    @Test
    void whatsappHistoryIsTenantIsolated() {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(new TenantContext.Identity(tenant, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        UUID company = seedCompanyWithRep(tenant, "+40700000000");
        whatsapp.dispatch(EmailKind.PAYROLL, company, PERIOD, "+40799999999", "Statele sunt gata.", null, null);

        TenantContext.set(new TenantContext.Identity(UUID.randomUUID(), UUID.randomUUID(), Role.TENANT_ADMIN, null));
        assertThat(whatsapp.history(EmailKind.PAYROLL, company, PERIOD)).isEmpty();
    }
}
