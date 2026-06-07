package ro.myfinance.extraction.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.audit.AuditRecorder;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.extraction.adapter.persistence.BankStatementRepository;
import ro.myfinance.extraction.adapter.persistence.BankTransactionRepository;
import ro.myfinance.extraction.domain.BankStatement;
import ro.myfinance.extraction.domain.BankTransaction;
import ro.myfinance.extraction.domain.StatementStatus;
import ro.myfinance.extraction.domain.TxnDirection;

/** Parses a bank-statement document into transactions. Tenant-scoped via RLS. */
@Service
@Transactional
public class BankStatementExtractionService {

    private static final Logger log = LoggerFactory.getLogger(BankStatementExtractionService.class);
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    private final BankStatementParserRegistry registry;
    private final BankStatementRepository statements;
    private final BankTransactionRepository transactions;
    private final AuditRecorder audit;

    public BankStatementExtractionService(BankStatementParserRegistry registry,
                                          BankStatementRepository statements,
                                          BankTransactionRepository transactions,
                                          AuditRecorder audit) {
        this.registry = registry;
        this.statements = statements;
        this.transactions = transactions;
        this.audit = audit;
    }

    public void extract(UUID documentId, UUID companyId, LocalDate periodMonth, byte[] bytes) {
        UUID tenantId = TenantContext.tenantId()
                .orElseThrow(() -> new IllegalStateException("No tenant bound"));

        String text = registry.extractText(bytes);
        Optional<BankStatementParser> parser = registry.find(text);
        if (parser.isEmpty()) {
            statements.save(new BankStatement(tenantId, documentId, companyId, periodMonth,
                    null, null, null, null, StatementStatus.NEEDS_REVIEW, false, 0));
            log.info("No parser matched statement document {} → NEEDS_REVIEW", documentId);
            return;
        }

        ParsedStatement parsed;
        try {
            parsed = parser.get().parse(text);
        } catch (RuntimeException e) {
            log.warn("Parse failed for document {}", documentId, e);
            statements.save(new BankStatement(tenantId, documentId, companyId, periodMonth,
                    null, null, null, null, StatementStatus.FAILED, false, 0));
            return;
        }

        boolean crossOk = crossCheck(parsed);
        StatementStatus status = crossOk ? StatementStatus.EXTRACTED : StatementStatus.NEEDS_REVIEW;
        BankStatement statement = statements.save(new BankStatement(tenantId, documentId, companyId,
                periodMonth, parsed.bankCode(), parsed.accountIban(), parsed.openingBalance(),
                parsed.closingBalance(), status, crossOk, parsed.transactions().size()));

        for (ParsedTransaction t : parsed.transactions()) {
            TxnDirection dir = t.amount().signum() < 0 ? TxnDirection.DEBIT : TxnDirection.CREDIT;
            transactions.save(new BankTransaction(tenantId, companyId, statement.getId(),
                    parsed.accountIban(), t.date(), t.amount(), dir, t.partnerName(), t.partnerIban(),
                    t.description(), t.ref(), t.balanceAfter()));
        }
        audit.record("STATEMENT_EXTRACTED", "bank_statement", statement.getId());
    }

    private boolean crossCheck(ParsedStatement p) {
        if (p.openingBalance() == null || p.closingBalance() == null) {
            return false;
        }
        BigDecimal sum = p.transactions().stream()
                .map(ParsedTransaction::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expected = p.openingBalance().add(sum);
        return expected.subtract(p.closingBalance()).abs().compareTo(TOLERANCE) <= 0;
    }
}
