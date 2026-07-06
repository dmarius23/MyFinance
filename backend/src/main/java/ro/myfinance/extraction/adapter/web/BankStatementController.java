package ro.myfinance.extraction.adapter.web;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.extraction.adapter.web.BankStatementDtos.OpenTransactionResponse;
import ro.myfinance.extraction.adapter.web.BankStatementDtos.StatementResponse;
import ro.myfinance.extraction.adapter.web.BankStatementDtos.TransactionResponse;
import ro.myfinance.extraction.application.ReconciliationService;

/** Read views over parsed bank statements/transactions. Firm staff only. */
@RestController
@RequestMapping("/api/v1/companies/{companyId}")
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'EMPLOYEE')")
public class BankStatementController {

    private final ReconciliationService recon;

    public BankStatementController(ReconciliationService recon) {
        this.recon = recon;
    }

    @GetMapping("/bank-statements")
    public List<StatementResponse> statements(@PathVariable UUID companyId,
                                              @RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        return recon.statementsForPeriod(companyId, period).stream()
                .map(StatementResponse::from).toList();
    }

    @GetMapping("/bank-transactions")
    public List<TransactionResponse> transactions(@PathVariable UUID companyId,
                                                  @RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        return recon.transactionsWithMatches(companyId, period).stream()
                .map(TransactionResponse::from).toList();
    }

    /** Transactions still open for allocation within a rolling window (add-payment picker). */
    @GetMapping("/bank-transactions/open")
    public List<OpenTransactionResponse> openTransactions(@PathVariable UUID companyId,
                                                          @RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period,
                                                          @RequestParam(value = "months", defaultValue = "18") int months) {
        return recon.openTransactions(companyId, period, months).stream()
                .map(OpenTransactionResponse::from).toList();
    }
}
