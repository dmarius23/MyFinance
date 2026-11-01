package ro.myfinance.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import ro.myfinance.reports.application.ReportEmailBuilder;

class ReportTemplateWiringTest {

    @Test
    void valuesCarryEveryFigureTheReportKindDeclares() {
        var values = ReportEmailBuilder.values(LocalDate.of(2026, 4, 1),
                CurrentMessageWordingTest.reportData(), "Maria Popescu", "ContaZone SRL");
        assertThat(values).containsKeys("companyName", "cui", "month", "revenue", "expenses",
                "grossProfit", "incomeTax", "netProfit", "signOff");
        assertThat(values.get("month")).isEqualTo("Aprilie 2026");
        assertThat(values.get("revenue")).isEqualTo("100 000.00");
        assertThat(values.get("expenses")).isEqualTo("61 000.00");
        assertThat(values.get("grossProfit")).isEqualTo("40 000.00");
        assertThat(values.get("netProfit")).isEqualTo("39 000.00");
        assertThat(values.get("companyName")).isEqualTo("Acme SRL");
    }

    @Test
    void expensesAreOperatingPlusIncomeTax() {
        var values = ReportEmailBuilder.values(LocalDate.of(2026, 4, 1),
                CurrentMessageWordingTest.reportData(), null, "ContaZone SRL");
        assertThat(values.get("expenses")).isEqualTo("61 000.00");
        assertThat(values.get("incomeTax")).isEqualTo("1 000.00");
        assertThat(values.get("signOff")).isEqualTo("ContaZone SRL");
    }
}
