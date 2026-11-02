package ro.myfinance.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Security;
import java.security.spec.ECGenParameterSpec;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.client.methods.HttpPost;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Guards the one thing about the Web Push stack that no other test touches: that the jose4j version we
 * declare in {@code pom.xml} is actually usable by the {@code web-push} library at runtime.
 *
 * <p>Why this exists. We must declare jose4j ourselves — web-push 5.1.2+ demotes it to {@code runtime}
 * scope, which drops it off our compile classpath even though {@code PushService.send()} names
 * {@code JoseException} in its throws clause. But we cannot simply match the version web-push expects:
 * the 0.7.x line carries two HIGH advisories (GHSA-7g24-qg88-p43q, GHSA-3677-xxcr-wjqv) and the SCA gate
 * rejects it. So we pin a patched 0.9.x, and because our declaration also wins on the RUNTIME classpath,
 * web-push ends up running against a jose4j it was not compiled against.
 *
 * <p>That is a binary-compatibility risk, and before this test nothing in the suite would have caught it:
 * a {@code NoSuchMethodError} inside VAPID signing would have surfaced only as a failed push in
 * production, where {@link ro.myfinance.notifications.adapter.external.MartijndwarsWebPushPort} swallows
 * every exception by design and merely returns {@code FAILED}.
 *
 * <p>{@code preparePost} is the hook: it performs the full VAPID JWS signing through jose4j and the
 * RFC-8291 payload encryption, but stops short of any network I/O — so this stays a fast unit test.
 */
class WebPushVapidSigningTest {

    /** The adapter registers BouncyCastle lazily; do the same here so key generation matches production. */
    @BeforeAll
    static void bouncyCastle() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private static KeyPair p256() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("ECDH", BouncyCastleProvider.PROVIDER_NAME);
        generator.initialize(new ECGenParameterSpec("prime256v1"));
        return generator.generateKeyPair();
    }

    /** A plausible browser subscription: the push endpoint plus the client's P-256 key and auth secret. */
    private static Notification subscription(KeyPair subscriber) {
        byte[] authSecret = new byte[16];
        new SecureRandom().nextBytes(authSecret);
        return new Notification(
                "https://fcm.googleapis.com/fcm/send/dGVzdC1lbmRwb2ludA",
                subscriber.getPublic(),
                authSecret,
                "{\"title\":\"Test\",\"body\":\"Document încărcat\"}".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The real assertion is "this does not blow up": signing exercises jose4j's JWS/ES256 path, so a
     * mismatched jose4j fails here with NoSuchMethodError or NoSuchMethodException rather than silently in
     * production. The header checks confirm it genuinely produced a VAPID authorization, not an empty post.
     */
    @Test
    void signsAVapidRequestWithTheDeclaredJose4jVersion() throws Exception {
        PushService service = new PushService(p256(), "mailto:push@mycompanyfinance.ro");

        HttpPost post = service.preparePost(subscription(p256()), Encoding.AES128GCM);

        assertThat(post).isNotNull();
        assertThat(post.getFirstHeader("Authorization")).isNotNull();
        assertThat(post.getFirstHeader("Authorization").getValue())
                .as("VAPID authorization header produced by jose4j JWS signing")
                .containsIgnoringCase("vapid")
                .contains("t=")   // the signed JWT
                .contains("k=");  // the VAPID public key
        assertThat(post.getFirstHeader("Content-Encoding").getValue()).isEqualTo("aes128gcm");
    }

    /** Payload encryption (RFC-8291) must also survive the dependency set, not just the signing. */
    @Test
    void encryptsThePayloadForTheSubscriber() throws Exception {
        PushService service = new PushService(p256(), "mailto:push@mycompanyfinance.ro");

        HttpPost post = service.preparePost(subscription(p256()), Encoding.AES128GCM);

        assertThat(post.getEntity()).isNotNull();
        assertThat(post.getEntity().getContentLength())
                .as("ciphertext is present and padded beyond the plaintext length")
                .isGreaterThan(0L);
    }

    /** Signing is non-deterministic (fresh ECDSA nonce + salt each time) and must not throw on reuse. */
    @Test
    void signsRepeatedlyWithoutError() throws Exception {
        PushService service = new PushService(p256(), "mailto:push@mycompanyfinance.ro");
        KeyPair subscriber = p256();

        assertThatCode(() -> {
            service.preparePost(subscription(subscriber), Encoding.AES128GCM);
            service.preparePost(subscription(subscriber), Encoding.AES128GCM);
        }).doesNotThrowAnyException();
    }
}
