package ro.myfinance.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ro.myfinance.access.application.CompanyImportService;
import ro.myfinance.access.application.CompanyImportService.Status;
import ro.myfinance.access.application.RepresentativeService;
import ro.myfinance.common.web.ConflictException;
import ro.myfinance.company.application.CompanyService;
import ro.myfinance.company.domain.Company;

class CompanyImportServiceTest {

    private final CompanyService companies = mock(CompanyService.class);
    private final RepresentativeService reps = mock(RepresentativeService.class);
    private final CompanyImportService svc = new CompanyImportService(companies, reps);

    private static byte[] csv(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    @Test
    void importsValidRowWithLenientValuesAndInvitesRep() {
        Company c = mock(Company.class);
        UUID cid = UUID.randomUUID();
        when(c.getId()).thenReturn(cid);
        when(companies.create(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(c);

        var res = svc.importCsv(csv("""
                name;cui;type;residence;vat;tax_regime;has_employees;rep_name;rep_email;rep_phone
                ACME SRL;RO12345678;SRL;Cluj;platitor;micro;da;Ion Pop;ion@acme.ro;0712345678
                """));

        assertThat(res.created()).isEqualTo(1);
        assertThat(res.total()).isEqualTo(1);
        // Lenient mapping: "platitor" → VAT_PAYER, "micro" → MICRO, "da" → true.
        verify(companies).create(eq("ACME SRL"), eq("RO12345678"), eq("SRL"), eq("Cluj"),
                eq("VAT_PAYER"), isNull(), eq("MICRO"), eq(true), isNull());
        verify(reps).inviteRepresentative(eq(cid), eq("Ion Pop"), eq("ion@acme.ro"), eq("0712345678"), eq(false));
    }

    @Test
    void normalizesBareNineDigitPhoneAndInvitesRep() {
        Company c = mock(Company.class);
        UUID cid = UUID.randomUUID();
        when(c.getId()).thenReturn(cid);
        when(companies.create(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(c);

        // Phone "712345678" (9 digits, no leading 0) — the real-world CSV format that used to be rejected.
        var res = svc.importCsv(csv("""
                name;cui;residence;vat;tax_regime;rep_name;rep_email;rep_phone
                ACME SRL;RO12345678;Cluj;platitor;micro;Ion Pop;ion@acme.ro;712345678
                """));

        assertThat(res.created()).isEqualTo(1);
        verify(reps).inviteRepresentative(eq(cid), eq("Ion Pop"), eq("ion@acme.ro"), eq("0712345678"), eq(false));
    }

    @Test
    void invitesRepEvenWhenPhoneIsUnrecognized() {
        Company c = mock(Company.class);
        UUID cid = UUID.randomUUID();
        when(c.getId()).thenReturn(cid);
        when(companies.create(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(c);

        var res = svc.importCsv(csv("""
                name;cui;residence;vat;tax_regime;rep_name;rep_email;rep_phone
                ACME SRL;RO12345678;Cluj;platitor;micro;Ion Pop;ion@acme.ro;garbage
                """));

        assertThat(res.created()).isEqualTo(1);
        // Bad phone dropped (null) — but the representative is still invited.
        verify(reps).inviteRepresentative(eq(cid), eq("Ion Pop"), eq("ion@acme.ro"), isNull(), eq(false));
    }

    @Test
    void backfillsRepresentativeOntoAnExistingCompany() {
        Company existing = mock(Company.class);
        UUID cid = UUID.randomUUID();
        when(existing.getId()).thenReturn(cid);
        when(companies.create(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new ConflictException("A company with CUI RO12345678 already exists"));
        when(companies.findByCui("RO12345678")).thenReturn(java.util.Optional.of(existing));

        var res = svc.importCsv(csv("""
                name;cui;residence;vat;tax_regime;rep_name;rep_email;rep_phone
                ACME SRL;RO12345678;Cluj;platitor;micro;Ion Pop;ion@acme.ro;712345678
                """));

        assertThat(res.created()).isZero();
        assertThat(res.skipped()).isEqualTo(1);           // company not re-created
        verify(reps).inviteRepresentative(eq(cid), eq("Ion Pop"), eq("ion@acme.ro"), eq("0712345678"), eq(false));
        assertThat(res.rows()).anyMatch(x -> x.status() == Status.SKIPPED && x.message().contains("representative added"));
    }

    @Test
    void reportsInvalidCuiAndSkipsDuplicates() {
        Company c = mock(Company.class);
        when(c.getId()).thenReturn(UUID.randomUUID());
        when(companies.create(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new ConflictException("A company with CUI RO12345678 already exists"));

        var res = svc.importCsv(csv("""
                name;cui;residence;vat;tax_regime
                BadCui SRL;XYZ;Cluj;platitor;micro
                Dup SRL;RO12345678;Cluj;neplatitor;profit
                """));

        assertThat(res.total()).isEqualTo(2);
        assertThat(res.invalid()).isEqualTo(1);  // XYZ fails the CUI pattern before create
        assertThat(res.skipped()).isEqualTo(1);  // valid CUI, but create reports a duplicate
        assertThat(res.created()).isZero();
        assertThat(res.rows()).anyMatch(r -> r.status() == Status.INVALID && r.message().contains("CUI"));
    }

    @Test
    void rejectsMissingRequiredColumns() {
        assertThatThrownBy(() -> svc.importCsv(csv("name;cui\nX;RO12\n")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("residence");
    }

    @Test
    void autoDetectsCommaDelimiterAndCreatesWithoutARep() {
        Company c = mock(Company.class);
        when(c.getId()).thenReturn(UUID.randomUUID());
        when(companies.create(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(c);

        var res = svc.importCsv(csv("""
                name,cui,residence,vat,tax_regime,has_employees
                Beta SRL,18547290,Bucuresti,neplatitor,profit,nu
                """));

        assertThat(res.created()).isEqualTo(1);
        verify(companies).create(eq("Beta SRL"), eq("18547290"), isNull(), eq("Bucuresti"),
                eq("NON_VAT_PAYER"), isNull(), eq("PROFIT"), eq(false), isNull());
        verify(reps, org.mockito.Mockito.never())
                .inviteRepresentative(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }
}
