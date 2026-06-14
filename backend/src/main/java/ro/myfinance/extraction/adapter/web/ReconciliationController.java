package ro.myfinance.extraction.adapter.web;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.extraction.adapter.web.BankStatementDtos.SetRequirementRequest;
import ro.myfinance.extraction.adapter.web.BankStatementDtos.TransactionResponse;
import ro.myfinance.extraction.application.ReconciliationService;
import ro.myfinance.extraction.adapter.web.BankStatementDtos.SuggestionResponse;
import ro.myfinance.extraction.application.ReconciliationService.CompanyCompleteness;
import ro.myfinance.extraction.application.ReconciliationService.DocumentStatus;

/** Reconciliation: accountant override + completeness summary. Firm staff only. */
@RestController
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'EMPLOYEE')")
public class ReconciliationController {

    private final ReconciliationService service;

    public ReconciliationController(ReconciliationService service) {
        this.service = service;
    }

    @PatchMapping("/api/v1/companies/{companyId}/bank-transactions/{txnId}/requirement")
    public TransactionResponse setRequirement(@PathVariable UUID companyId, @PathVariable UUID txnId,
                                              @RequestBody SetRequirementRequest r) {
        return TransactionResponse.from(service.setRequirement(txnId, r.requiresDocument(), r.reason()));
    }

    @PostMapping("/api/v1/companies/{companyId}/bank-transactions/{txnId}/matches")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void link(@PathVariable UUID companyId, @PathVariable UUID txnId,
                     @RequestBody BankStatementDtos.MatchRequest r) {
        service.link(companyId, txnId, r.invoiceId(), r.amount());
    }

    @DeleteMapping("/api/v1/companies/{companyId}/bank-transactions/{txnId}/matches/{invoiceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlink(@PathVariable UUID companyId, @PathVariable UUID txnId, @PathVariable UUID invoiceId) {
        service.unlink(companyId, txnId, invoiceId);
    }

    @GetMapping("/api/v1/reconciliation/summary")
    public List<CompanyCompleteness> summary(@RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        return service.completenessSummary(period);
    }

    @GetMapping("/api/v1/companies/{companyId}/document-status")
    public List<DocumentStatus> documentStatus(@PathVariable UUID companyId,
                                               @RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        return service.documentStatuses(companyId, period);
    }

    @GetMapping("/api/v1/companies/{companyId}/match-suggestions")
    public List<SuggestionResponse> suggestions(@PathVariable UUID companyId,
                                                @RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        return service.suggestions(companyId, period).stream().map(SuggestionResponse::from).toList();
    }
}
