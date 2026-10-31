package ro.myfinance.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.myfinance.common.messaging.MessageTemplate;
import ro.myfinance.common.messaging.MessageTemplateKind;
import ro.myfinance.common.messaging.MessageTemplateRepository;
import ro.myfinance.common.messaging.MessageTemplateService;
import ro.myfinance.common.messaging.TemplateRenderer;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;

class MessageTemplateServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER = UUID.fromString("99999999-9999-9999-9999-999999999999");

    private MessageTemplateRepository repo;
    private MessageTemplateService service;

    @BeforeEach
    void setUp() {
        repo = mock(MessageTemplateRepository.class);
        service = new MessageTemplateService(repo, new TemplateRenderer());
        TenantContext.set(new TenantContext.Identity(TENANT, USER, Role.TENANT_ADMIN, null));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void rendersTheBuiltInDefaultWhenNoRowExists() {
        when(repo.findByTenantIdAndKind(TENANT, MessageTemplateKind.PAYROLL)).thenReturn(Optional.empty());
        var out = service.render(MessageTemplateKind.PAYROLL,
                Map.of("month", "Aprilie 2026", "taxDeadline", "25 Aprilie 2026", "signOff", "Firma SRL"));
        assertThat(out.subject()).isEqualTo("State de plată — Aprilie 2026");
        assertThat(out.body()).contains("pontajul pentru luna Aprilie 2026").endsWith("Firma SRL");
    }

    @Test
    void rendersTheTenantRowWhenPresent() {
        MessageTemplate row = new MessageTemplate(TENANT, MessageTemplateKind.PAYROLL);
        row.setSubject("Salarii {{month}}");
        row.setBody("Salut!\nLuna {{month}}.\n{{signOff}}");
        when(repo.findByTenantIdAndKind(TENANT, MessageTemplateKind.PAYROLL)).thenReturn(Optional.of(row));
        var out = service.render(MessageTemplateKind.PAYROLL,
                Map.of("month", "Aprilie 2026", "signOff", "Firma SRL"));
        assertThat(out.subject()).isEqualTo("Salarii Aprilie 2026");
        assertThat(out.body()).isEqualTo("Salut!\nLuna Aprilie 2026.\nFirma SRL");
    }

    /** A tenant may reword the body only; a null subject keeps the built-in one. */
    @Test
    void nullSubjectOnTheRowFallsBackToTheDefaultSubject() {
        MessageTemplate row = new MessageTemplate(TENANT, MessageTemplateKind.REPORT);
        row.setSubject(null);
        row.setBody("Raport {{month}}");
        when(repo.findByTenantIdAndKind(TENANT, MessageTemplateKind.REPORT)).thenReturn(Optional.of(row));
        assertThat(service.render(MessageTemplateKind.REPORT, Map.of("month", "Aprilie 2026")).subject())
                .isEqualTo("Raport financiar — Aprilie 2026");
    }

    /** A blank (whitespace-only) subject also falls back to the built-in default. */
    @Test
    void blankSubjectOnTheRowFallsBackToTheDefaultSubject() {
        MessageTemplate row = new MessageTemplate(TENANT, MessageTemplateKind.REPORT);
        row.setSubject("   ");
        row.setBody("Raport {{month}}");
        when(repo.findByTenantIdAndKind(TENANT, MessageTemplateKind.REPORT)).thenReturn(Optional.of(row));
        assertThat(service.render(MessageTemplateKind.REPORT, Map.of("month", "Aprilie 2026")).subject())
                .isEqualTo("Raport financiar — Aprilie 2026");
    }

    @Test
    void effectiveReportsWhetherTheKindIsCustomized() {
        when(repo.findByTenantIdAndKind(TENANT, MessageTemplateKind.TAX)).thenReturn(Optional.empty());
        assertThat(service.effective(MessageTemplateKind.TAX).customized()).isFalse();

        MessageTemplate row = new MessageTemplate(TENANT, MessageTemplateKind.TAX);
        row.setBody("Al nostru");
        when(repo.findByTenantIdAndKind(TENANT, MessageTemplateKind.TAX)).thenReturn(Optional.of(row));
        var eff = service.effective(MessageTemplateKind.TAX);
        assertThat(eff.customized()).isTrue();
        assertThat(eff.body()).isEqualTo("Al nostru");
    }

    @Test
    void effectiveAllReturnsEveryKind() {
        when(repo.findByTenantId(TENANT)).thenReturn(List.of());
        assertThat(service.effectiveAll())
                .hasSize(MessageTemplateKind.values().length)
                .allSatisfy(e -> assertThat(e.customized()).isFalse());
    }

    /** effectiveAll() merges customized rows over defaults: one with a row is customized, others default. */
    @Test
    void effectiveAllMergesCustomizedRowsWithDefaults() {
        MessageTemplate customPayroll = new MessageTemplate(TENANT, MessageTemplateKind.PAYROLL);
        customPayroll.setSubject("Salarii firma");
        customPayroll.setBody("Salarii pentru luna curenta");
        when(repo.findByTenantId(TENANT)).thenReturn(List.of(customPayroll));

        List<MessageTemplateService.Effective> all = service.effectiveAll();

        assertThat(all).hasSize(MessageTemplateKind.values().length);

        // PAYROLL should be customized with the row's values
        var payrollEff = all.stream()
                .filter(e -> e.kind() == MessageTemplateKind.PAYROLL)
                .findFirst()
                .orElseThrow();
        assertThat(payrollEff.customized()).isTrue();
        assertThat(payrollEff.subject()).isEqualTo("Salarii firma");
        assertThat(payrollEff.body()).isEqualTo("Salarii pentru luna curenta");

        // TAX should NOT be customized, so its body should contain the built-in default text
        var taxEff = all.stream()
                .filter(e -> e.kind() == MessageTemplateKind.TAX)
                .findFirst()
                .orElseThrow();
        assertThat(taxEff.customized()).isFalse();
        assertThat(taxEff.body()).contains("Sumele de plată"); // part of the built-in TAX default, not PAYROLL

        // All other non-PAYROLL entries must be customized=false
        all.stream()
                .filter(e -> e.kind() != MessageTemplateKind.PAYROLL)
                .forEach(e -> assertThat(e.customized()).isFalse());
    }

    @Test
    void saveStampsTheCurrentUserAndNormalizesLineEndings() {
        when(repo.findByTenantIdAndKind(TENANT, MessageTemplateKind.PAYROLL)).thenReturn(Optional.empty());
        service.save(MessageTemplateKind.PAYROLL, "Subiect", "Prima\r\nA doua");
        var captor = org.mockito.ArgumentCaptor.forClass(MessageTemplate.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getBody()).isEqualTo("Prima\nA doua");
        assertThat(captor.getValue().getUpdatedBy()).isEqualTo(USER);
        assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT);
    }

    @Test
    void resetDeletesTheTenantRow() {
        service.reset(MessageTemplateKind.PAYROLL);
        verify(repo).deleteByTenantIdAndKind(TENANT, MessageTemplateKind.PAYROLL);
    }

    @Test
    void failsClosedWhenNoTenantIsBound() {
        TenantContext.clear();
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> service.render(MessageTemplateKind.TAX, Map.of()))
                .isInstanceOf(IllegalStateException.class);
    }
}
