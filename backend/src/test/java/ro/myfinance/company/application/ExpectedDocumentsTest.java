package ro.myfinance.company.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import ro.myfinance.company.domain.Company;
import ro.myfinance.company.domain.CompanyStatus;

class ExpectedDocumentsTest {

    private final ExpectedDocuments expected = new ExpectedDocuments();

    @Test
    void owesPayrollOnlyWhenHasEmployeesIsTrue() {
        assertThat(expected.owesPayroll(company(true))).isTrue();
        assertThat(expected.owesPayroll(company(false))).isFalse();
        assertThat(expected.owesPayroll(company(null))).isFalse(); // fail-open on unknown profile
    }

    @Test
    void owesBalanceForActiveCompaniesOnly() {
        assertThat(expected.owesBalance(companyStatus(CompanyStatus.ACTIVE))).isTrue();
        assertThat(expected.owesBalance(companyStatus(CompanyStatus.INACTIVE))).isFalse();
    }

    private Company company(Boolean hasEmployees) {
        Company c = mock(Company.class);
        when(c.getHasEmployees()).thenReturn(hasEmployees);
        return c;
    }

    private Company companyStatus(CompanyStatus status) {
        Company c = mock(Company.class);
        when(c.getStatus()).thenReturn(status);
        return c;
    }
}
