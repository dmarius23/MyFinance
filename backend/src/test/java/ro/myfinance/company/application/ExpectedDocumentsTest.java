package ro.myfinance.company.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import ro.myfinance.company.domain.Company;

class ExpectedDocumentsTest {

    private final ExpectedDocuments expected = new ExpectedDocuments();

    @Test
    void owesPayrollOnlyWhenHasEmployeesIsTrue() {
        assertThat(expected.owesPayroll(company(true))).isTrue();
        assertThat(expected.owesPayroll(company(false))).isFalse();
        assertThat(expected.owesPayroll(company(null))).isFalse(); // fail-open on unknown profile
    }

    private Company company(Boolean hasEmployees) {
        Company c = mock(Company.class);
        when(c.getHasEmployees()).thenReturn(hasEmployees);
        return c;
    }
}
