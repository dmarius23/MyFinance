package ro.myfinance.extraction.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * The currency the extractor read has to reach the stored row. Exercised through Fields, which is the
 * one shape every intake path (PDF, receipt image) funnels into before persistence.
 */
class InvoiceCurrencyPersistedTest {

    private InvoiceExtractionService.Fields fields(String currency) {
        return new InvoiceExtractionService.Fields("Bolt Operations OU", null,
                new BigDecimal("59.90"), LocalDate.of(2026, 9, 24), "EXTRACTED",
                null, "50109730", "32", Boolean.FALSE, currency);
    }

    @Test
    void fieldsCarryTheCurrency() {
        assertThat(fields("EUR").currency()).isEqualTo("EUR");
    }

    @Test
    void identifyClientPartyPreservesTheCurrency() {
        // This helper rebuilds Fields; a rebuild that forgot the currency would silently reset it to RON.
        InvoiceExtractionService.Fields out =
                InvoiceExtractionService.identifyClientParty(fields("EUR"), "50109730", "AGILE SRL");
        assertThat(out.currency()).isEqualTo("EUR");
    }
}
