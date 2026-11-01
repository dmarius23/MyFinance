package ro.myfinance.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import ro.myfinance.payroll.application.PayrollEmailBuilder;

class PayrollTemplateWiringTest {

    /** The deadline is supplied by the caller (resolved from reference data), formatted here. */
    @Test
    void valuesFormatTheSuppliedDeadline() {
        var values = PayrollEmailBuilder.values(LocalDate.of(2026, 4, 17), "Acme SRL",
                "Maria Popescu", "ContaZone SRL", LocalDate.of(2026, 5, 25));
        assertThat(values.get("month")).isEqualTo("Aprilie 2026");
        assertThat(values.get("taxDeadline")).isEqualTo("25 Mai 2026");
        assertThat(values.get("companyName")).isEqualTo("Acme SRL");
        assertThat(values.get("signOff")).isEqualTo("Maria Popescu\nContaZone SRL");
    }

    /** A changed statutory day reaches the client's email unchanged in shape. */
    @Test
    void aDifferentConfiguredDayFlowsThrough() {
        var values = PayrollEmailBuilder.values(LocalDate.of(2026, 4, 1), "Acme SRL",
                null, "Firma", LocalDate.of(2026, 5, 20));
        assertThat(values.get("taxDeadline")).isEqualTo("20 Mai 2026");
    }

    /**
     * The year has to roll over with the month — December's payroll is due 25 January of the NEXT year.
     * Naming the period's own month, as this once did, never exposed this case.
     */
    @Test
    void decemberDeadlineRollsIntoTheFollowingYear() {
        var values = PayrollEmailBuilder.values(LocalDate.of(2026, 12, 31), "Acme SRL",
                null, "Firma", LocalDate.of(2027, 1, 25));
        assertThat(values.get("month")).isEqualTo("Decembrie 2026");
        assertThat(values.get("taxDeadline")).isEqualTo("25 Ianuarie 2027");
    }

    @Test
    void normalizesAnyDayOfMonthToTheFirst() {
        assertThat(PayrollEmailBuilder.values(LocalDate.of(2026, 12, 31), null, null, "Firma",
                LocalDate.of(2027, 1, 25)).get("month")).isEqualTo("Decembrie 2026");
    }
}
