package ro.myfinance.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import ro.myfinance.settings.adapter.external.AnafHttpIbanSource;
import ro.myfinance.settings.adapter.external.AnafIbanParser;

/**
 * Deterministic parsing of the ANAF IBAN catalogue, against real fixtures captured from the live site
 * (index page, one county page, one treasury PDF) — no network. Proves county discovery, PDF-link
 * extraction, the four embedded-code IBAN lookups and residence parsing.
 */
class AnafIbanParserTest {

    private static final String BASE = "https://static.anaf.ro/static/10/Anaf/AsistentaContribuabili_r/iban2014";

    private String fixtureText(String name) throws IOException {
        return new String(fixtureBytes(name), StandardCharsets.UTF_8);
    }

    private byte[] fixtureBytes(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/anaf-iban/" + name)) {
            assertThat(in).as("fixture %s", name).isNotNull();
            return in.readAllBytes();
        }
    }

    @Test
    void discoversTheCountyPagesAndDropsNonCounties() throws IOException {
        List<String> urls = AnafIbanParser.countyPageUrls(fixtureText("index.html"), BASE);

        assertThat(urls).hasSize(41); // the 41 județe (București links sector PDFs directly, no .htm)
        assertThat(urls).contains(
                BASE + "/Alba.htm",
                BASE + "/Cluj.htm",
                BASE + "/Bistrita_nasaud.htm",
                BASE + "/Caras_Severin.htm",
                BASE + "/Satu_Mare.htm");
        assertThat(urls).noneMatch(u -> u.endsWith("bugetstat.htm") || u.endsWith("bugetlocal.htm")
                || u.endsWith("bass.htm") || u.endsWith("fnuass.htm") || u.endsWith("somaj.htm"));
    }

    @Test
    void extractsTreasuryPdfLinksFromACountyPage() throws IOException {
        List<String> pdfs = AnafIbanParser.pdfLinks(fixtureText("Alba.htm"), BASE);

        assertThat(pdfs).isNotEmpty();
        assertThat(pdfs).allMatch(u -> u.startsWith("https://") && u.contains("iban_TREZ") && u.endsWith(".pdf"));
        assertThat(pdfs).contains(BASE + "/iban_TREZ001_TREZ002.pdf");
    }

    @Test
    void extractsBucurestiTreasuryPdfsLinkedDirectlyOnTheIndex() throws IOException {
        // Bucuresti (municipal + 6 sectors) has no county page — its PDFs are linked straight on the index.
        List<String> pdfs = AnafIbanParser.pdfLinks(fixtureText("index.html"), BASE);

        assertThat(pdfs).containsExactlyInAnyOrder(
                BASE + "/iban_TREZ000_TREZ700.pdf", // Municipiul Bucuresti
                BASE + "/iban_TREZ000_TREZ701.pdf", // Sector 1
                BASE + "/iban_TREZ000_TREZ702.pdf",
                BASE + "/iban_TREZ000_TREZ703.pdf",
                BASE + "/iban_TREZ000_TREZ704.pdf",
                BASE + "/iban_TREZ000_TREZ705.pdf",
                BASE + "/iban_TREZ000_TREZ706.pdf"); // Sector 6
        // the "cheltuieli" reference PDFs on the index are not treasuries and must be excluded
        assertThat(pdfs).noneMatch(u -> u.toLowerCase().contains("cheltuieli"));
    }

    @Test
    void extractsIbansAndResidenceFromABucurestiSectorPdf() throws IOException {
        String text = AnafHttpIbanSource.extractText(fixtureBytes("iban_TREZ000_TREZ701.pdf"));

        assertThat(AnafIbanParser.residence(text)).isEqualTo("Sector 1");
        assertThat(AnafIbanParser.ibanByCode(text, AnafIbanParser.CODE_5503)).isEqualTo("RO14TREZ7015503XXXXXXXXX");
        assertThat(AnafIbanParser.ibanByCode(text, AnafIbanParser.CODE_CAM)).isEqualTo("RO54TREZ70120A470300XXXX");
        assertThat(AnafIbanParser.ibanByCode(text, AnafIbanParser.CODE_TVA_INTERN)).isEqualTo("RO32TREZ70120A100101XTVA");
        assertThat(AnafIbanParser.ibanByCode(text, AnafIbanParser.CODE_TVA_EXTERN)).isEqualTo("RO76TREZ70120A100102XTVA");
    }

    @Test
    void extractsTheFourTargetIbansByEmbeddedCode() throws IOException {
        String text = AnafHttpIbanSource.extractText(fixtureBytes("iban_TREZ001_TREZ002.pdf"));

        String cont = AnafIbanParser.ibanByCode(text, AnafIbanParser.CODE_5503);
        String cam = AnafIbanParser.ibanByCode(text, AnafIbanParser.CODE_CAM);
        String tvaIntern = AnafIbanParser.ibanByCode(text, AnafIbanParser.CODE_TVA_INTERN);
        String tvaExtern = AnafIbanParser.ibanByCode(text, AnafIbanParser.CODE_TVA_EXTERN);

        assertThat(cont).isEqualTo("RO31TREZ0025503XXXXXXXXX");
        assertThat(cam).isEqualTo("RO02TREZ00220A470300XXXX");
        assertThat(tvaIntern).isEqualTo("RO77TREZ00220A100101XTVA");
        assertThat(tvaExtern).isEqualTo("RO24TREZ00220A100102XTVA");
        // every target IBAN is a well-formed 24-char treasury IBAN carrying its budget code
        assertThat(List.of(cont, cam, tvaIntern, tvaExtern)).allMatch(i -> i.length() == 24 && i.startsWith("RO"));
    }

    @Test
    void returnsNullForAnAbsentCode() throws IOException {
        String text = AnafHttpIbanSource.extractText(fixtureBytes("iban_TREZ001_TREZ002.pdf"));
        assertThat(AnafIbanParser.ibanByCode(text, "99Z999999")).isNull();
    }

    @Test
    void readsResidenceFromThePdfHeader() throws IOException {
        String text = AnafHttpIbanSource.extractText(fixtureBytes("iban_TREZ001_TREZ002.pdf"));
        assertThat(AnafIbanParser.residence(text)).isEqualTo("Alba Iulia");
    }

    @Test
    void residenceStripsTreasuryTypeWordsAndKeepsMultiWordTowns() {
        assertThat(AnafIbanParser.residence("Trezoreria judeteana Alba")).isEqualTo("Alba");
        assertThat(AnafIbanParser.residence("Trezorerie operativa Municipiul  Alba Iulia")).isEqualTo("Alba Iulia");
        assertThat(AnafIbanParser.residence("Trezorerie operativa Orasul Campeni")).isEqualTo("Campeni");
    }
}
