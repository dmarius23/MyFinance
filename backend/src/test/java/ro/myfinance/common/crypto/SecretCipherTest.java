package ro.myfinance.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretCipherTest {

    // A deterministic 32-byte test key (content irrelevant for the round-trip properties).
    private final SecretCipher cipher = new SecretCipher(Base64.getEncoder().encodeToString(new byte[32]));

    @Test
    void roundTripsIncludingUnicode() {
        String secret = "re_live_9xAbö!#&=";
        String enc = cipher.encrypt(secret);
        assertThat(enc).isNotEqualTo(secret);
        assertThat(cipher.decrypt(enc)).isEqualTo(secret);
    }

    @Test
    void sameInputEncryptsDifferentlyEachTime() {
        // Fresh random IV per encryption → no ciphertext reuse.
        assertThat(cipher.encrypt("token")).isNotEqualTo(cipher.encrypt("token"));
    }

    @Test
    void passesNullThrough() {
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.decrypt(null)).isNull();
    }

    @Test
    void tamperedCiphertextIsRejected() {
        byte[] raw = Base64.getDecoder().decode(cipher.encrypt("secret"));
        raw[raw.length - 1] ^= 0x01; // flip a bit in the GCM tag
        String tampered = Base64.getEncoder().encodeToString(raw);
        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unconfiguredKeyFailsClosed() {
        SecretCipher none = new SecretCipher("");
        assertThat(none.isConfigured()).isFalse();
        assertThatThrownBy(() -> none.encrypt("x")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsWrongLengthKey() {
        assertThatThrownBy(() -> new SecretCipher(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class);
    }
}
