package ro.myfinance.common.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StringNormalizerTest {

    @Test
    void stripDiacriticsFoldsRomanianLettersKeepingCaseAndSpacing() {
        // ș/ț comma-below and ş/ţ cedilla variants, both cases; spacing + punctuation preserved.
        assertThat(StringNormalizer.stripDiacritics("Situație Profit"))
                .isEqualTo("Situatie Profit");
        assertThat(StringNormalizer.stripDiacritics("ȘTEFAN & FIII S.R.L."))
                .isEqualTo("STEFAN & FIII S.R.L.");
        assertThat(StringNormalizer.stripDiacritics("Întârziere"))
                .isEqualTo("Intarziere");
    }

    @Test
    void stripDiacriticsMapsNullToEmpty() {
        assertThat(StringNormalizer.stripDiacritics(null)).isEmpty();
    }

    @Test
    void foldLowerIsAccentInsensitiveAndKeepsSpaces() {
        assertThat(StringNormalizer.foldLower("Situație PROFIT")).isEqualTo("situatie profit");
        assertThat(StringNormalizer.foldLower("Bilanț contabil")).isEqualTo("bilant contabil");
        assertThat(StringNormalizer.foldLower(null)).isEmpty();
    }

    @Test
    void alnumUpperStripsEverythingButAsciiAlphanumerics() {
        assertThat(StringNormalizer.alnumUpper("S.C. Țăranu & Fiii S.R.L."))
                .isEqualTo("SCTARANUFIIISRL");
        assertThat(StringNormalizer.alnumUpper("2024-08 / August")).isEqualTo("202408AUGUST");
        assertThat(StringNormalizer.alnumUpper(null)).isEmpty();
    }

    @Test
    void alnumUpperAndFoldLowerAgreeOnLettersModuloCaseAndSeparators() {
        String raw = "Ștefănești";
        assertThat(StringNormalizer.alnumUpper(raw)).isEqualTo("STEFANESTI");
        assertThat(StringNormalizer.foldLower(raw)).isEqualTo("stefanesti");
    }
}
