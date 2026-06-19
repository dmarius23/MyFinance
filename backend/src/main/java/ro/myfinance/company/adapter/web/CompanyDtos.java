package ro.myfinance.company.adapter.web;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import ro.myfinance.company.domain.Company;
import ro.myfinance.company.domain.CompanyStatus;

public final class CompanyDtos {

    private CompanyDtos() {
    }

    public record CreateCompanyRequest(@NotBlank String legalName, @NotBlank String cui,
                                       String entityType, String locality, String vatStatus,
                                       String vatPeriod, String taxRegime, Boolean hasEmployees,
                                       UUID responsibleUserId) {
    }

    public record UpdateCompanyRequest(String legalName, String entityType, String locality,
                                       String vatStatus, String vatPeriod, String taxRegime,
                                       Boolean hasEmployees, UUID responsibleUserId) {
    }

    public record SetStatusRequest(@jakarta.validation.constraints.NotNull CompanyStatus status) {
    }

    public record CompanyResponse(UUID id, String legalName, String cui, String entityType,
                                  String locality, String vatStatus, String vatPeriod, String taxRegime,
                                  Boolean hasEmployees, UUID responsibleUserId, CompanyStatus status) {
        public static CompanyResponse from(Company c) {
            return new CompanyResponse(c.getId(), c.getLegalName(), c.getCui(), c.getEntityType(),
                    c.getLocality(), c.getVatStatus(), c.getVatPeriod(), c.getTaxRegime(),
                    c.getHasEmployees(), c.getResponsibleUserId(), c.getStatus());
        }
    }
}
