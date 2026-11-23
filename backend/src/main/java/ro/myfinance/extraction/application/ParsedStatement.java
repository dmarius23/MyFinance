package ro.myfinance.extraction.application;

import java.math.BigDecimal;
import java.util.List;

public record ParsedStatement(String bankCode, String accountIban, BigDecimal openingBalance,
                              BigDecimal closingBalance, List<ParsedTransaction> transactions,
                              String currency) {
    public ParsedStatement {
        // Never null: the column is NOT NULL and most parsers legitimately cannot tell.
        currency = currency == null ? "RON" : currency.toUpperCase(java.util.Locale.ROOT);
    }
}
