package ro.myfinance.extraction.application;

import java.math.BigDecimal;
import java.util.List;

public record ParsedStatement(String bankCode, String accountIban, BigDecimal openingBalance,
                              BigDecimal closingBalance, List<ParsedTransaction> transactions) {
}
