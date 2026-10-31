package ro.myfinance.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import ro.myfinance.payroll.application.PayrollEmailBuilder;

class PayrollTemplateWiringTest {

    @Test
    void valuesCarryTheMonthAndTheTwentyFifthDeadline() {
        var values = PayrollEmailBuilder.values(LocalDate.of(2026, 4, 17), "Acme SRL",
                "Maria Popescu", "ContaZone SRL");
        assertThat(values.get("month")).isEqualTo("Aprilie 2026");
        assertThat(values.get("taxDeadline")).isEqualTo("25 Aprilie 2026");
        assertThat(values.get("companyName")).isEqualTo("Acme SRL");
        assertThat(values.get("signOff")).isEqualTo("Maria Popescu\nContaZone SRL");
    }

    @Test
    void normalizesAnyDayOfMonthToTheFirst() {
        assertThat(PayrollEmailBuilder.values(LocalDate.of(2026, 12, 31), null, null, "Firma")
                .get("month")).isEqualTo("Decembrie 2026");
    }
}
