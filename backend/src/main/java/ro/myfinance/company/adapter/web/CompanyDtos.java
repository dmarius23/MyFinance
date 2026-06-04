package ro.myfinance.company.adapter.web;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import ro.myfinance.company.domain.Company;
import ro.myfinance.company.domain.CompanyStatus;
import ro.myfinance.company.domain.TreasuryAccount;

public final class CompanyDtos {

    private CompanyDtos() {
    }

    public record CreateCompanyRequest(@NotBlank String legalName, @NotBlank String cui,
                                       String entityType, String locality, String vatStatus,
                                       String vatPeriod, UUID responsibleUserId) {
    }

    public record UpdateCompanyRequest(String legalName, String entityType, String locality,
                                       String vatStatus, String vatPeriod, UUID responsibleUserId) {
    }

    public record SetStatusRequest(@jakarta.validation.constraints.NotNull CompanyStatus status) {
    }

    public record CreateTreasuryAccountRequest(@NotBlank String taxType, String locality,
                                               @NotBlank String iban, String label) {
    }

    public record CompanyResponse(UUID id, String legalName, String cui, String entityType,
                                  String locality, String vatStatus, String vatPeriod,
                                  UUID responsibleUserId, CompanyStatus status) {
        public static CompanyResponse from(Company c) {
            return new CompanyResponse(c.getId(), c.getLegalName(), c.getCui(), c.getEntityType(),
                    c.getLocality(), c.getVatStatus(), c.getVatPeriod(), c.getResponsibleUserId(),
                    c.getStatus());
        }
    }

    public record TreasuryAccountResponse(UUID id, String taxType, String locality, String iban, String label) {
        public static TreasuryAccountResponse from(TreasuryAccount t) {
            return new TreasuryAccountResponse(t.getId(), t.getTaxType(), t.getLocality(),
                    t.getIban(), t.getLabel());
        }
    }
}
