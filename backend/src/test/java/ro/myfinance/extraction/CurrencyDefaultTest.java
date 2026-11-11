package ro.myfinance.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ro.myfinance.extraction.domain.BankTransaction;
import ro.myfinance.extraction.domain.Invoice;
import ro.myfinance.extraction.domain.TxnDirection;

/**
 * The column is NOT NULL, so a row must never reach the database with a null currency. Every existing
 * row is backfilled to RON by the migration's DEFAULT; these assert the Java side agrees, so a caller
 * that passes null gets RON rather than a constraint violation at runtime.
 */
class CurrencyDefaultTest {

    @Test
    void invoiceWithoutACurrencyIsRon() {
        Invoice inv = new Invoice(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.of(2026, 9, 1), "ACME SRL", null, new BigDecimal("100.00"),
                LocalDate.of(2026, 9, 10), "f.pdf", "EXTRACTED", null);
        assertThat(inv.getCurrency()).isEqualTo("RON");
    }

    @Test
    void invoiceKeepsAnExplicitCurrency() {
        Invoice inv = new Invoice(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.of(2026, 9, 1), "Bolt", null, new BigDecimal("59.90"),
                LocalDate.of(2026, 9, 24), "bolt.pdf", "EXTRACTED", "EUR");
        assertThat(inv.getCurrency()).isEqualTo("EUR");
    }

    @Test
    void reExtractionCanChangeTheCurrency() {
        Invoice inv = new Invoice(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.of(2026, 9, 1), "Bolt", null, new BigDecimal("59.90"),
                LocalDate.of(2026, 9, 24), "bolt.pdf", "EXTRACTED", "RON");
        inv.updateExtraction("Bolt", null, new BigDecimal("59.90"),
                LocalDate.of(2026, 9, 24), "bolt.pdf", "EXTRACTED", "EUR");
        assertThat(inv.getCurrency()).isEqualTo("EUR");
    }

    @Test
    void transactionWithoutACurrencyIsRon() {
        BankTransaction t = new BankTransaction(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "RO49AAAA1B31007593840000", LocalDate.of(2026, 9, 4), new BigDecimal("-59.90"),
                TxnDirection.DEBIT, "Bolt", null, "ride", "ref", null, null);
        assertThat(t.getCurrency()).isEqualTo("RON");
    }
}
