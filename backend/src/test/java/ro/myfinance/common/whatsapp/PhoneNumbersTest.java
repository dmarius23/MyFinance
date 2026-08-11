package ro.myfinance.common.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PhoneNumbersTest {

    @Test
    void normalisesCommonRomanianForms() {
        assertThat(PhoneNumbers.toE164("0733434442", "+40")).isEqualTo("+40733434442"); // national trunk
        assertThat(PhoneNumbers.toE164("+40733434442", "+40")).isEqualTo("+40733434442"); // already E.164
        assertThat(PhoneNumbers.toE164("0040733434442", "+40")).isEqualTo("+40733434442"); // 00 prefix
        assertThat(PhoneNumbers.toE164("0733 434 442", "+40")).isEqualTo("+40733434442"); // spaces stripped
        assertThat(PhoneNumbers.toE164("733434442", "+40")).isEqualTo("+40733434442"); // no leading 0
    }

    @Test
    void leavesBlankInputUntouched() {
        assertThat(PhoneNumbers.toE164(null, "+40")).isNull();
        assertThat(PhoneNumbers.toE164("", "+40")).isEmpty();
    }
}
