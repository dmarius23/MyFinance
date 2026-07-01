package ro.myfinance.extraction.application;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ParsedInvoice(String supplierName, String supplierIban, BigDecimal totalAmount,
                            LocalDate invoiceDate, String clientCif, String invoiceNumber) {
}
