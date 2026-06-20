package ro.myfinance.taxpayments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import ro.myfinance.taxpayments.application.AnafDeclarationExtractor;
import ro.myfinance.taxpayments.domain.DeclarationType;
import ro.myfinance.taxpayments.domain.ParsedDeclaration;
import ro.myfinance.taxpayments.domain.TaxCategory;
import ro.myfinance.taxpayments.domain.TaxObligation;

/**
 * Verifies extraction against the real ANAF sample PDFs. The PDFs carry PII (CNP, salaries) so they are
 * gitignored; the test skips when they're absent (e.g. in CI). Run locally after dropping them in
 * src/test/resources/fixtures/anaf/{D100,D112,D300}.pdf.
 */
class AnafDeclarationExtractorTest {

    private final AnafDeclarationExtractor extractor = new AnafDeclarationExtractor();

    private ParsedDeclaration extractFixture(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/anaf/" + name)) {
            assumeTrue(in != null, "fixture missing: " + name + " (PII PDF, gitignored)");
            return extractor.extract(in.readAllBytes());
        }
    }

    private static Map<TaxCategory, BigDecimal> byCategory(ParsedDeclaration d) {
        return d.obligations().stream().collect(Collectors.toMap(
                TaxObligation::category, TaxObligation::amount, BigDecimal::add));
    }

    @Test
    void d100_micro_tax_itemized_442_with_header_mismatch() throws IOException {
        ParsedDeclaration d = extractFixture("D100.pdf");
        assertThat(d.type()).isEqualTo(DeclarationType.D100);
        assertThat(d.cui()).isEqualTo("49443957");
        assertThat(d.period()).isEqualTo(YearMonth.of(2026, 3));
        assertThat(byCategory(d).get(TaxCategory.IMPOZIT)).isEqualByComparingTo("442");
        assertThat(d.computedTotal()).isEqualByComparingTo("442");
        // Header total reads 884 → must flag a mismatch (itemized 442 is authoritative).
        assertThat(d.declaredTotal()).isEqualByComparingTo("884");
        assertThat(d.totalsMismatch()).isTrue();
    }

    @Test
    void d112_social_contributions_self_validate_to_1776() throws IOException {
        ParsedDeclaration d = extractFixture("D112.pdf");
        assertThat(d.type()).isEqualTo(DeclarationType.D112);
        assertThat(d.cui()).isEqualTo("49443957");
        assertThat(d.period()).isEqualTo(YearMonth.of(2026, 3));
        Map<TaxCategory, BigDecimal> m = byCategory(d);
        assertThat(m.get(TaxCategory.IMPOZIT)).isEqualByComparingTo("264"); // impozit pe venit (602)
        assertThat(m.get(TaxCategory.CAS)).isEqualByComparingTo("1015");    // 412
        assertThat(m.get(TaxCategory.CASS)).isEqualByComparingTo("406");    // 432
        assertThat(m.get(TaxCategory.CAM)).isEqualByComparingTo("91");      // 480
        assertThat(d.computedTotal()).isEqualByComparingTo("1776");
        assertThat(d.totalsMismatch()).isFalse(); // matches totalPlata_A
    }

    @Test
    void d100_falls_back_to_de_restituit_when_de_plata_is_zero() {
        // suma_plata = 0 → use suma_rest ("De restituit") as a negative refund, not a payment.
        String xml = "<?xml version=\"1.0\"?>"
                + "<declaratie100 luna=\"03\" an=\"2026\" cui=\"49443957\" den=\"X SRL\" totalPlata_A=\"0\">"
                + "<obligatie cod_oblig=\"121\" scadenta=\"25.04.2026\" suma_dat=\"0\" suma_plata=\"0\" suma_rest=\"500\"/>"
                + "</declaratie100>";
        ParsedDeclaration d = extractor.parseXmlBytes(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(d.obligations()).hasSize(1);
        TaxObligation o = d.obligations().get(0);
        assertThat(o.refund()).isTrue();
        assertThat(o.amount()).isEqualByComparingTo("-500");
        assertThat(d.computedTotal()).isEqualByComparingTo("0"); // nothing to pay
    }

    @Test
    void d300_vat_payable_from_row41_not_header() throws IOException {
        ParsedDeclaration d = extractFixture("D300.pdf");
        assertThat(d.type()).isEqualTo(DeclarationType.D300);
        assertThat(d.period()).isEqualTo(YearMonth.of(2026, 4));
        assertThat(byCategory(d).get(TaxCategory.TVA)).isEqualByComparingTo("18930"); // R41_2, not totalPlata_A
        assertThat(d.computedTotal()).isEqualByComparingTo("18930");
    }
}
