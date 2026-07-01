package ro.myfinance.extraction.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
    private final ReconciliationService reconciliation;
    private final AuditRecorder audit;

    public BankStatementExtractionService(BankStatementParserRegistry registry,
                                          BankStatementRepository statements,
                                          BankTransactionRepository transactions,
                                          ReconciliationService reconciliation,
                                          AuditRecorder audit) {
        this.registry = registry;
        this.statements = statements;
        this.transactions = transactions;
        this.reconciliation = reconciliation;
        this.audit = audit;
    }

    public void extract(UUID documentId, UUID companyId, LocalDate periodMonth, byte[] bytes) {
        UUID tenantId = TenantContext.tenantId()
                .orElseThrow(() -> new IllegalStateException("No tenant bound"));

        // Re-extraction path (a rescan, or a re-publish after a new bank parser was added). document_id
        // is unique, so a prior statement must be cleared before re-inserting. A statement that already
        // extracted successfully is left untouched — re-running the same parser would only risk dropping
        // its manual reconciliation links — and we just re-run matching. A prior parse that produced
        // nothing usable (NEEDS_REVIEW/FAILED — e.g. before this parser existed) is deleted (the DB
        // cascades its transactions and their matches) so the new parser can populate it.
        Optional<BankStatement> prior = statements.findByDocumentId(documentId);
        if (prior.isPresent()) {
            BankStatement es = prior.get();
            if (es.getStatus() == StatementStatus.EXTRACTED && es.getTxnCount() > 0) {
                reconciliation.matchPeriod(companyId, periodMonth);
                return;
            }
            statements.deleteByDocumentId(documentId);
            statements.flush(); // release the unique(document_id) before the re-insert below
        }

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

        // Dedup against ALL of the company's stored transactions (across statements and upload
        // periods), so the same statement uploaded under two different month-labels — or overlapping
        // statements — never double-records a transaction. The key includes the exact date and running
        // balance, so genuinely distinct (incl. recurring) transactions in other months are kept.
        Set<String> seen = new HashSet<>();
        for (BankTransaction existing : transactions.findByCompanyId(companyId)) {
            seen.add(dedupKey(existing.getAccountIban(), existing.getTxnDate(), existing.getAmount(),
                    existing.getBalanceAfter(), existing.getDescription(), existing.getRef()));
        }

        boolean hadNullAmount = false;
        List<ParsedTransaction> unique = new ArrayList<>();
        for (ParsedTransaction t : parsed.transactions()) {
            if (t.amount() == null) {
                hadNullAmount = true; // amount couldn't be derived → skip the row, flag for review
                continue;
            }
            String key = dedupKey(parsed.accountIban(), t.date(), t.amount(), t.balanceAfter(),
                    t.description(), t.ref());
            if (seen.add(key)) {
                unique.add(t);
            }
        }

        boolean crossOk = crossCheck(parsed);
        StatementStatus status = (crossOk && !hadNullAmount)
                ? StatementStatus.EXTRACTED : StatementStatus.NEEDS_REVIEW;

        BankStatement statement = statements.save(new BankStatement(tenantId, documentId, companyId,
                periodMonth, parsed.bankCode(), parsed.accountIban(), parsed.openingBalance(),
                parsed.closingBalance(), status, crossOk, unique.size()));

        for (ParsedTransaction t : unique) {
            TxnDirection dir = t.amount().signum() < 0 ? TxnDirection.DEBIT : TxnDirection.CREDIT;
            transactions.save(new BankTransaction(tenantId, companyId, statement.getId(),
                    parsed.accountIban(), t.date(), t.amount(), dir, t.partnerName(), t.partnerIban(),
                    t.description(), t.ref(), t.balanceAfter()));
        }
        reconciliation.classify(statement.getId());
        reconciliation.matchPeriod(companyId, periodMonth);
        audit.record("STATEMENT_EXTRACTED", "bank_statement", statement.getId());
    }

    private String dedupKey(String accountIban, java.time.LocalDate date, java.math.BigDecimal amount,
                            java.math.BigDecimal balanceAfter, String description, String ref) {
        if (balanceAfter != null) {
            return "B|" + accountIban + "|" + date + "|" + amount.stripTrailingZeros().toPlainString()
                    + "|" + balanceAfter.stripTrailingZeros().toPlainString();
        }
        return "F|" + accountIban + "|" + date + "|" + amount.stripTrailingZeros().toPlainString() + "|"
                + ReconText.normalize(description) + "|" + (ref == null ? "" : ref);
    }

    private boolean crossCheck(ParsedStatement p) {
        if (p.openingBalance() == null || p.closingBalance() == null
                || p.transactions().stream().anyMatch(t -> t.amount() == null)) {
            return false;
        }
        BigDecimal sum = p.transactions().stream()
                .map(ParsedTransaction::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expected = p.openingBalance().add(sum);
        return expected.subtract(p.closingBalance()).abs().compareTo(TOLERANCE) <= 0;
    }
}
