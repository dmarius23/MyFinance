package ro.myfinance.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import ro.myfinance.common.email.SignOff;

/**
 * {@code names} is the {{signOff}} block: the sender and firm name lines only. The closing line above it
 * is prose the tenant owns, which is why it is no longer part of this call.
 */
class SignOffNamesTest {

    @Test
    void joinsSenderAndFirm() {
        assertThat(SignOff.names("Maria Popescu", "ContaZone SRL"))
                .isEqualTo("Maria Popescu\nContaZone SRL");
    }

    @Test
    void dropsBlankSender() {
        assertThat(SignOff.names("  ", "ContaZone SRL")).isEqualTo("ContaZone SRL");
        assertThat(SignOff.names(null, "ContaZone SRL")).isEqualTo("ContaZone SRL");
    }

    @Test
    void dropsBlankFirm() {
        assertThat(SignOff.names("Maria Popescu", null)).isEqualTo("Maria Popescu");
    }

    /** Both blank yields empty, so the renderer elides the whole line rather than leaving a dangling \n. */
    @Test
    void bothBlankYieldsEmpty() {
        assertThat(SignOff.names(null, " ")).isEmpty();
    }

    @Test
    void trimsEachName() {
        assertThat(SignOff.names(" Maria ", " ContaZone ")).isEqualTo("Maria\nContaZone");
    }
}
