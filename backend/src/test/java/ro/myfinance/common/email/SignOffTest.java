package ro.myfinance.common.email;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The closing block every client-facing message shares. The firm's name is the invariant: whatever else
 * is missing, the client must be able to see which accounting firm wrote to them.
 */
class SignOffTest {

    @Test
    void signsWithSenderThenFirm() {
        assertThat(SignOff.block("O zi bună,", "Maria Pop", "ContaZone SRL"))
                .isEqualTo("O zi bună,\nMaria Pop\nContaZone SRL");
    }

    @Test
    void omitsTheSenderWhenUnknown() {
        assertThat(SignOff.block("O zi bună,", null, "ContaZone SRL"))
                .isEqualTo("O zi bună,\nContaZone SRL");
        assertThat(SignOff.block("O zi bună,", "   ", "ContaZone SRL"))
                .isEqualTo("O zi bună,\nContaZone SRL");
    }

    /** A SUPER_ADMIN has no tenant — degrade to the bare closing rather than printing "null". */
    @Test
    void omitsTheFirmWhenUnknown() {
        assertThat(SignOff.block("O zi bună,", "Maria Pop", null))
                .isEqualTo("O zi bună,\nMaria Pop");
        assertThat(SignOff.block("O zi bună,", null, "  ")).isEqualTo("O zi bună,");
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertThat(SignOff.block("Cu stimă,", "  Maria Pop  ", "  ContaZone SRL  "))
                .isEqualTo("Cu stimă,\nMaria Pop\nContaZone SRL");
    }
}
