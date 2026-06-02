package ro.myfinance.mod03_company.adapter.web;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.mod03_company.adapter.web.CompanyDtos.CompanyResponse;
import ro.myfinance.mod03_company.adapter.web.CompanyDtos.CreateCompanyRequest;
import ro.myfinance.mod03_company.adapter.web.CompanyDtos.CreateTreasuryAccountRequest;
import ro.myfinance.mod03_company.adapter.web.CompanyDtos.SetStatusRequest;
import ro.myfinance.mod03_company.adapter.web.CompanyDtos.TreasuryAccountResponse;
import ro.myfinance.mod03_company.adapter.web.CompanyDtos.UpdateCompanyRequest;
import ro.myfinance.mod03_company.application.CompanyService;

/** MOD-03 — client company management. Firm staff (admin/employee) only. */
@RestController
@RequestMapping("/api/v1/companies")
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'EMPLOYEE')")
public class CompanyController {

    private final CompanyService service;

    public CompanyController(CompanyService service) {
        this.service = service;
    }

    @GetMapping
    public List<CompanyResponse> list() {
        return service.list().stream().map(CompanyResponse::from).toList();
    }

    @GetMapping("/{id}")
    public CompanyResponse get(@PathVariable UUID id) {
        return CompanyResponse.from(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CompanyResponse create(@Valid @RequestBody CreateCompanyRequest r) {
        return CompanyResponse.from(service.create(r.legalName(), r.cui(), r.entityType(),
                r.locality(), r.vatStatus(), r.vatPeriod(), r.responsibleUserId()));
    }

    @PutMapping("/{id}")
    public CompanyResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateCompanyRequest r) {
        return CompanyResponse.from(service.update(id, r.legalName(), r.entityType(), r.locality(),
                r.vatStatus(), r.vatPeriod(), r.responsibleUserId()));
    }

    @PatchMapping("/{id}/status")
    public CompanyResponse setStatus(@PathVariable UUID id, @Valid @RequestBody SetStatusRequest r) {
        return CompanyResponse.from(service.setStatus(id, r.status()));
    }

    @GetMapping("/{id}/treasury-accounts")
    public List<TreasuryAccountResponse> listTreasuryAccounts(@PathVariable UUID id) {
        return service.listTreasuryAccounts(id).stream().map(TreasuryAccountResponse::from).toList();
    }

    @PostMapping("/{id}/treasury-accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public TreasuryAccountResponse addTreasuryAccount(@PathVariable UUID id,
                                                      @Valid @RequestBody CreateTreasuryAccountRequest r) {
        return TreasuryAccountResponse.from(
                service.addTreasuryAccount(id, r.taxType(), r.locality(), r.iban(), r.label()));
    }
}
