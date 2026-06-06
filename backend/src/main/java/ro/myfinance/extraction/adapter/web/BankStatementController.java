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
import ro.myfinance.extraction.adapter.persistence.BankStatementRepository;
import ro.myfinance.extraction.adapter.persistence.BankTransactionRepository;
import ro.myfinance.extraction.adapter.web.BankStatementDtos.StatementResponse;
import ro.myfinance.extraction.adapter.web.BankStatementDtos.TransactionResponse;
import ro.myfinance.extraction.domain.BankStatement;

/** Read views over parsed bank statements/transactions. Firm staff only. */
@RestController
@RequestMapping("/api/v1/companies/{companyId}")
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'EMPLOYEE')")
public class BankStatementController {

    private final BankStatementRepository statements;
    private final BankTransactionRepository transactions;

    public BankStatementController(BankStatementRepository statements,
                                   BankTransactionRepository transactions) {
        this.statements = statements;
        this.transactions = transactions;
    }

    @GetMapping("/bank-statements")
    public List<StatementResponse> statements(@PathVariable UUID companyId,
                                              @RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        return statements.findByCompanyIdAndPeriodMonth(companyId, period).stream()
                .map(StatementResponse::from).toList();
    }

    @GetMapping("/bank-transactions")
    public List<TransactionResponse> transactions(@PathVariable UUID companyId,
                                                  @RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        List<UUID> ids = statements.findByCompanyIdAndPeriodMonth(companyId, period).stream()
                .map(BankStatement::getId).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return transactions.findByStatementIdInOrderByTxnDateDesc(ids).stream()
                .map(TransactionResponse::from).toList();
    }
}
