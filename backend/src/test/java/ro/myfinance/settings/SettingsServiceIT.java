package ro.myfinance.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.ConflictException;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.settings.application.SettingsService;
import ro.myfinance.settings.domain.ResidenceTreasuryAccount;
import ro.myfinance.support.AbstractPostgresIT;

class SettingsServiceIT extends AbstractPostgresIT {

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000011");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000022");

    @Autowired SettingsService service;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void asTenant(UUID tenantId) {
        TenantContext.set(new TenantContext.Identity(tenantId, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        jdbc.update("insert into tenant(id, name, status, plan) values (?, ?, 'ACTIVE', 'STANDARD') on conflict do nothing",
                tenantId, "T-" + tenantId);
    }

    @Test
    void returnsDefaultVatRateWhenNoRowExists() {
        asTenant(TENANT_A);
        assertThat(service.getSettings().getVatRate()).isEqualByComparingTo("21.00");
    }

    @Test
    void getSettingsIsIdempotent() {
        asTenant(TENANT_A);
        service.getSettings();
        service.getSettings();
        assertThat(service.getSettings().getVatRate()).isEqualByComparingTo("21.00");
    }

    @Test
    void updatesVatRate() {
        asTenant(TENANT_A);
        service.updateRates(new BigDecimal("19.00"), new BigDecimal("3.00"), new BigDecimal("16.00"));
        assertThat(service.getSettings().getVatRate()).isEqualByComparingTo("19.00");
    }

    @Test
    void rejectsVatRateAbove100() {
        asTenant(TENANT_A);
        assertThatThrownBy(() -> service.updateRates(new BigDecimal("101"), new BigDecimal("3.00"), new BigDecimal("16.00")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeVatRate() {
        asTenant(TENANT_A);
        assertThatThrownBy(() -> service.updateRates(new BigDecimal("-1"), new BigDecimal("3.00"), new BigDecimal("16.00")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addsAndListsTreasuryAccounts() {
        asTenant(TENANT_A);
        service.addTreasuryAccount("Cluj-Napoca", List.of("TVA"), "RO49AAAA1B31007593840000", "TVA Cluj");
        service.addTreasuryAccount("Cluj-Napoca", List.of("IMPOZIT_SALARII", "CAS"), "RO49AAAA1B31007593840001", "Sal Cluj");
        assertThat(service.listTreasuryAccounts()).hasSize(2);
    }

    @Test
    void rejectsDuplicateResidenceIban() {
        asTenant(TENANT_A);
        service.addTreasuryAccount("Cluj-Napoca", List.of("TVA"), "RO49AAAA1B31007593840000", "TVA Cluj");
        assertThatThrownBy(() -> service.addTreasuryAccount(
                "Cluj-Napoca", List.of("IMPOZIT_PROFIT"), "RO49AAAA1B31007593840000", "other"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void deletesTreasuryAccount() {
        asTenant(TENANT_A);
        ResidenceTreasuryAccount account = service.addTreasuryAccount("Cluj-Napoca", List.of("TVA"), "RO49...", "TVA");
        service.deleteTreasuryAccount(account.getId());
        assertThat(service.listTreasuryAccounts()).isEmpty();
    }

    @Test
    void deleteNonExistentThrowsNotFound() {
        asTenant(TENANT_A);
        assertThatThrownBy(() -> service.deleteTreasuryAccount(UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void tenantBCannotSeeTenantASettings() {
        asTenant(TENANT_A);
        service.updateRates(new BigDecimal("5.00"), new BigDecimal("3.00"), new BigDecimal("16.00"));
        service.addTreasuryAccount("Cluj-Napoca", List.of("TVA"), "RO49...", "TVA A");

        asTenant(TENANT_B);
        assertThat(service.getSettings().getVatRate()).isEqualByComparingTo("21.00");
        assertThat(service.listTreasuryAccounts()).isEmpty();
    }

    @Test
    void tenantBCannotDeleteTenantAAccount() {
        asTenant(TENANT_A);
        ResidenceTreasuryAccount account = service.addTreasuryAccount("Cluj-Napoca", List.of("TVA"), "RO49...", "TVA");

        asTenant(TENANT_B);
        assertThatThrownBy(() -> service.deleteTreasuryAccount(account.getId()))
                .isInstanceOf(NotFoundException.class);
    }
}
