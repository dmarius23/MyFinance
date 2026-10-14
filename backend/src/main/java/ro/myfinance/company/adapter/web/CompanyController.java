package ro.myfinance.company.adapter.web;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.common.web.PageResponse;
import ro.myfinance.company.adapter.web.CompanyDtos.CompanyResponse;
import ro.myfinance.company.adapter.web.CompanyDtos.CreateCompanyRequest;
import ro.myfinance.company.adapter.web.CompanyDtos.SetStatusRequest;
import ro.myfinance.company.adapter.web.CompanyDtos.UpdateCompanyRequest;
import ro.myfinance.company.application.CompanyRegistryLookup;
import ro.myfinance.settings.application.TreasuryResidenceResolver;
import ro.myfinance.company.application.CompanyService;

/** Client company management. Firm staff (admin/employee) only. */
@RestController
@RequestMapping("/api/v1/companies")
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'EMPLOYEE')")
public class CompanyController {

    private final CompanyService service;
    private final CompanyRegistryLookup registry;
    private final TreasuryResidenceResolver residences;

    public CompanyController(CompanyService service, CompanyRegistryLookup registry,
                             TreasuryResidenceResolver residences) {
        this.service = service;
        this.registry = registry;
        this.residences = residences;
    }

    /**
     * Official data for a fiscal code, to prefill the add-company form. 404 when the registry doesn't
     * know the CUI or is unreachable — the caller then just keeps typing; this never blocks creation.
     */
    @GetMapping("/lookup")
    public CompanyRegistryLookup.RegistryCompany lookup(@RequestParam("cui") String cui) {
        CompanyRegistryLookup.RegistryCompany c = registry.byCui(cui).orElseThrow(() ->
                new ro.myfinance.common.web.NotFoundException("CUI not found in the ANAF registry: " + cui));
        // ANAF's fiscal locality is often a commune with no treasury of its own, so map it onto a residence
        // that actually exists in the treasury reference — that is the value the payment accounts key on.
        return residences.resolve(c.fiscalAuthority(), c.fiscalLocality(), c.fiscalCounty(),
                        java.time.LocalDate.now())
                .map(c::withTreasuryResidence)
                .orElse(c);
    }

    @GetMapping
    public List<CompanyResponse> list() {
        return service.list().stream().map(CompanyResponse::from).toList();
    }

    /** Paged variant for the Companies directory (infinite scroll). Full list above stays for pickers. */
    @GetMapping("/page")
    public PageResponse<CompanyResponse> listPage(@RequestParam(name = "q", defaultValue = "") String q,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "25") int size) {
        return PageResponse.from(service.listPage(q, page, size).map(CompanyResponse::from));
    }

    @GetMapping("/{id}")
    public CompanyResponse get(@PathVariable UUID id) {
        return CompanyResponse.from(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CompanyResponse create(@Valid @RequestBody CreateCompanyRequest r) {
        return CompanyResponse.from(service.create(r.legalName(), r.cui(), r.entityType(),
                r.locality(), r.vatStatus(), r.vatPeriod(), r.taxRegime(), r.hasEmployees(),
                r.responsibleUserId()));
    }

    @PutMapping("/{id}")
    public CompanyResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateCompanyRequest r) {
        return CompanyResponse.from(service.update(id, r.cui(), r.legalName(), r.entityType(), r.locality(),
                r.vatStatus(), r.vatPeriod(), r.taxRegime(), r.hasEmployees(), r.responsibleUserId()));
    }

    @PatchMapping("/{id}/status")
    public CompanyResponse setStatus(@PathVariable UUID id, @Valid @RequestBody SetStatusRequest r) {
        return CompanyResponse.from(service.setStatus(id, r.status()));
    }
}
