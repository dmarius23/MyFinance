package ro.myfinance.extraction.adapter.external;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import ro.myfinance.extraction.application.BankStatementParser;
import ro.myfinance.extraction.application.BankStatementParserRegistry;
import ro.myfinance.extraction.application.ParsedStatement;
import ro.myfinance.extraction.application.ParsedTransaction;

/**
 * End-to-end extraction pipeline test for the newer inline ING layout, exercising exactly what production
 * runs on upload: raw PDF bytes → {@link BankStatementParserRegistry#extractText} (PDFBox,
 * sort-by-position) → {@link BankStatementParserRegistry#find} (parser selection) → parse. It builds a
 * synthetic, PII-free ING statement whose transaction rows print date + payee + amount + balance as
 * separately positioned columns on one baseline (which sort-by-position linearizes into a single line) —
 * the shape that yielded zero transactions before the fix. Asserts the ING parser is SELECTED over the
 * generic fallback and that transactions are extracted and reconcile.
 */
class IngStatementRegistryPipelineTest {

    // Production registry order: bank-specific parsers before the generic running-balance fallback.
    private final BankStatementParserRegistry registry = new BankStatementParserRegistry(List.of(
            new RevolutStatementParser(),
            new BrdStatementParser(),
            new BancaTransilvaniaStatementParser(),
            new IngStatementParser(),
            new GenericRunningBalanceParser()));

    @Test
    void extractsInlineIngStatementThroughFullPipeline() throws Exception {
        byte[] pdf = buildInlineIngPdf();

        String text = registry.extractText(pdf);
        Optional<BankStatementParser> chosen = registry.find(text);

        // The specific ING parser must win — not the generic fallback (the bug shipped 0 txns silently).
        assertThat(chosen).isPresent();
        assertThat(chosen.get()).isInstanceOf(IngStatementParser.class);

        ParsedStatement s = chosen.get().parse(text);
        assertThat(s.transactions()).hasSize(3);
        assertThat(s.openingBalance()).isEqualByComparingTo("1000.00");
        assertThat(s.closingBalance()).isEqualByComparingTo("1252.79");

        ParsedTransaction first = s.transactions().get(0);
        assertThat(first.amount()).isEqualByComparingTo("302.50");       // credit positive
        assertThat(first.partnerName()).contains("DR PET SRL");          // payee from the inline date line
        assertThat(first.partnerIban()).isEqualTo("RO85INGB0000999917661723");
        assertThat(first.ref()).isEqualTo("9201");
        assertThat(s.transactions().get(1).amount()).isEqualByComparingTo("-775.71"); // debit negative

        // Cross-check: opening + Σ(signed amounts) == closing.
        BigDecimal sum = s.transactions().stream().map(ParsedTransaction::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(s.openingBalance().add(sum)).isEqualByComparingTo("1252.79");
    }

    /** A one-page ING statement whose rows put date/payee/amount/balance in separate x-columns on one line. */
    private byte[] buildInlineIngPdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float y = 800f;
                put(cs, font, 40, y, "Extras de cont"); y -= 18;
                put(cs, font, 40, y, "Nr.8 / 31.08.2026"); y -= 18;
                put(cs, font, 40, y, "BIC code (SWIFT): INGBROBU"); y -= 18;
                put(cs, font, 40, y,
                        "Sold initial: Total creditari (2): Total debitari (1): Sold final: Perioada"); y -= 18;
                put(cs, font, 40, y, "1000.00 1028.50 -775.71 1252.79 01 - 31.08.2026"); y -= 18;
                put(cs, font, 40, y,
                        "Data procesarii Beneficiar / Ordonator Debitari Creditari Sold intermediar"); y -= 18;

                // Transaction 1 (credit): columns on one baseline.
                row(cs, font, y, "01.08.2026", "DR PET SRL", "302.50", "1,302.50"); y -= 18;
                put(cs, font, 40, y, "9201 RO85INGB0000999917661723"); y -= 18;
                // Transaction 2 (debit).
                row(cs, font, y, "01.08.2026", "REVISALPLUS", "-775.71", "526.79"); y -= 18;
                put(cs, font, 40, y, "9203 Cumparare POS"); y -= 18;
                // Transaction 3 (credit).
                row(cs, font, y, "03.08.2026", "MADE TO MEZUM SRL", "726.00", "1,252.79"); y -= 18;
                put(cs, font, 40, y, "9207 RO15BTRLRONCRT0648848101");
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private void row(PDPageContentStream cs, PDType1Font font, float y,
                     String date, String payee, String amount, String balance) throws Exception {
        put(cs, font, 40, y, date);
        put(cs, font, 120, y, payee);
        put(cs, font, 380, y, amount);
        put(cs, font, 470, y, balance);
    }

    private void put(PDPageContentStream cs, PDType1Font font, float x, float y, String s) throws Exception {
        cs.beginText();
        cs.setFont(font, 9);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
    }
}
