package ro.myfinance.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
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
        service.updateRates(new BigDecimal("19.00"), new BigDecimal("3.00"), new BigDecimal("16.00"), null);
        assertThat(service.getSettings().getVatRate()).isEqualByComparingTo("19.00");
    }

    @Test
    void rejectsVatRateAbove100() {
        asTenant(TENANT_A);
        assertThatThrownBy(() -> service.updateRates(new BigDecimal("101"), new BigDecimal("3.00"), new BigDecimal("16.00"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeVatRate() {
        asTenant(TENANT_A);
        assertThatThrownBy(() -> service.updateRates(new BigDecimal("-1"), new BigDecimal("3.00"), new BigDecimal("16.00"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addsAndListsTreasuryAccounts() {
        asTenant(TENANT_A);
        service.addTreasuryAccount("Cluj-Napoca", "RO-CAM", "RO-IMP", "RO-CASS", "RO-CAS", "RO-TVA");
        service.addTreasuryAccount("Brașov", null, "RO-IMP-BV", null, null, null);
        assertThat(service.listTreasuryAccounts()).hasSize(2);
        var cluj = service.listTreasuryAccounts().stream()
                .filter(a -> a.getResidence().equals("Cluj-Napoca")).findFirst().orElseThrow();
        assertThat(cluj.getIbanTva()).isEqualTo("RO-TVA");
        assertThat(cluj.getIbanImpozite()).isEqualTo("RO-IMP");
    }

    @Test
    void rejectsDuplicateResidence() {
        asTenant(TENANT_A);
        service.addTreasuryAccount("Cluj-Napoca", null, "RO-IMP", null, null, null);
        assertThatThrownBy(() -> service.addTreasuryAccount("Cluj-Napoca", null, "RO-OTHER", null, null, null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void updatesTreasuryIbans() {
        asTenant(TENANT_A);
        ResidenceTreasuryAccount a = service.addTreasuryAccount("Cluj-Napoca", null, "RO-IMP", null, null, null);
        service.updateTreasuryAccount(a.getId(), "RO-CAM2", "RO-IMP2", null, null, "RO-TVA2");
        ResidenceTreasuryAccount updated = service.listTreasuryAccounts().get(0);
        assertThat(updated.getIbanCam()).isEqualTo("RO-CAM2");
        assertThat(updated.getIbanTva()).isEqualTo("RO-TVA2");
        assertThat(updated.getIbanCass()).isNull();
    }

    @Test
    void deletesTreasuryAccount() {
        asTenant(TENANT_A);
        ResidenceTreasuryAccount account = service.addTreasuryAccount("Cluj-Napoca", null, "RO-IMP", null, null, null);
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
        service.updateRates(new BigDecimal("5.00"), new BigDecimal("3.00"), new BigDecimal("16.00"), null);
        service.addTreasuryAccount("Cluj-Napoca", null, "RO-IMP-A", null, null, null);

        asTenant(TENANT_B);
        assertThat(service.getSettings().getVatRate()).isEqualByComparingTo("21.00");
        assertThat(service.listTreasuryAccounts()).isEmpty();
    }

    @Test
    void tenantBCannotDeleteTenantAAccount() {
        asTenant(TENANT_A);
        ResidenceTreasuryAccount account = service.addTreasuryAccount("Cluj-Napoca", null, "RO-IMP", null, null, null);

        asTenant(TENANT_B);
        assertThatThrownBy(() -> service.deleteTreasuryAccount(account.getId()))
                .isInstanceOf(NotFoundException.class);
    }
}
