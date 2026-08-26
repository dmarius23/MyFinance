package ro.myfinance.company.application;

import org.springframework.stereotype.Component;
import ro.myfinance.company.domain.Company;
import ro.myfinance.company.domain.CompanyStatus;

/**
 * Which documents/declarations a company is EXPECTED to submit for a period, derived from its fiscal
 * profile. Powers the "needs attention" completeness filter so a company is never flagged for something it
 * isn't required to file.
 *
 * <p><b>Fail-open:</b> a blank/unknown profile field means "not expected" — the filter never makes a false
 * accusation. Cadence is intentionally ignored for now (a required type with zero uploads this month is
 * flagged regardless of monthly/quarterly/annual filing); see
 * {@code backend/docs/design-history/completeness-filter-design.md}.
 */
@Component
public class ExpectedDocuments {

    /** Payroll (state de plată) is owed only by companies that have employees. */
    public boolean owesPayroll(Company company) {
        return Boolean.TRUE.equals(company.getHasEmployees());
    }

    /** A monthly trial balance (balanță) is owed by every active company. */
    public boolean owesBalance(Company company) {
        return company.getStatus() == CompanyStatus.ACTIVE;
    }
}
