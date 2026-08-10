package ro.myfinance.taxpayments.domain;

import java.math.BigDecimal;

/**
 * One itemized fiscal obligation (creanță) persisted with a declaration: the ANAF budget code and its
 * amount, as parsed from the declaration XML. The human-readable short label is derived at display time
 * from the code (see {@code ObligationLabels}) — only the authoritative code + amount are stored.
 */
public record ObligationLine(String cod, BigDecimal amount) {
}
