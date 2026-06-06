package ro.myfinance.extraction.adapter.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import ro.myfinance.extraction.domain.BankStatement;
import ro.myfinance.extraction.domain.BankTransaction;

public final class BankStatementDtos {

    private BankStatementDtos() {
    }

    public record StatementResponse(UUID id, String bankCode, String accountIban,
                                    BigDecimal openingBalance, BigDecimal closingBalance,
                                    String status, boolean crossCheckOk, int txnCount) {
        public static StatementResponse from(BankStatement s) {
            return new StatementResponse(s.getId(), s.getBankCode(), s.getAccountIban(),
                    s.getOpeningBalance(), s.getClosingBalance(), s.getStatus().name(),
                    s.isCrossCheckOk(), s.getTxnCount());
        }
    }

    public record TransactionResponse(UUID id, UUID statementId, LocalDate txnDate, BigDecimal amount,
                                      String direction, String partnerName, String partnerIban,
                                      String description, BigDecimal balanceAfter) {
        public static TransactionResponse from(BankTransaction t) {
            return new TransactionResponse(t.getId(), t.getStatementId(), t.getTxnDate(), t.getAmount(),
                    t.getDirection().name(), t.getPartnerName(), t.getPartnerIban(), t.getDescription(),
                    t.getBalanceAfter());
        }
    }
}
