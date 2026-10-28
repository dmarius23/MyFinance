package ro.myfinance.ingestion.adapter.external;

import java.util.List;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.beans.factory.annotation.Autowired;

/** Verifies Google Pub/Sub push JWTs before accepting a Gmail notification. */
@Component
public class GmailPubSubTokenVerifier {

    private static final String GOOGLE_ISSUER = "https://accounts.google.com";
    private static final String GOOGLE_CERTS = "https://www.googleapis.com/oauth2/v3/certs";

    private final GmailOAuthProperties properties;
    private final JwtDecoder decoder;

    @Autowired
    public GmailPubSubTokenVerifier(GmailOAuthProperties properties) {
        this.properties = properties;
        NimbusJwtDecoder googleDecoder = NimbusJwtDecoder.withJwkSetUri(GOOGLE_CERTS).build();
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(
                "aud", value -> value != null && value.contains(properties.pubSubAudience()));
        googleDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(GOOGLE_ISSUER), audience));
        this.decoder = googleDecoder;
    }

    /** Test seam for exercising claim validation without contacting Google's JWK endpoint. */
    public GmailPubSubTokenVerifier(GmailOAuthProperties properties, JwtDecoder decoder) {
        this.properties = properties;
        this.decoder = decoder;
    }

    public Jwt verify(String authorizationHeader) {
        if (properties.pubSubAudience() == null || properties.pubSubAudience().isBlank()) {
            throw new AccessDeniedException("Gmail Pub/Sub audience is not configured");
        }
        // The audience alone is NOT an authenticator: any Google service account can mint an OIDC
        // token for an arbitrary audience, so without pinning the caller's identity this endpoint
        // accepts forged notifications from anyone with a GCP account. Treating the pin as optional
        // meant the weaker configuration was also the easier one — so it is now required, and the
        // endpoint fails closed until it is set.
        String expectedServiceAccount = properties.pubSubServiceAccount();
        if (expectedServiceAccount == null || expectedServiceAccount.isBlank()) {
            throw new AccessDeniedException("Gmail Pub/Sub service account is not configured — set "
                    + "GMAIL_PUBSUB_SERVICE_ACCOUNT to the push identity's email");
        }
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw new AccessDeniedException("Missing Pub/Sub bearer token");
        }
        try {
            Jwt jwt = decoder.decode(authorizationHeader.substring("Bearer ".length()).trim());
            if (!expectedServiceAccount.equalsIgnoreCase(jwt.getClaimAsString("email"))) {
                throw new AccessDeniedException("Unexpected Pub/Sub service account");
            }
            return jwt;
        } catch (JwtException e) {
            throw new AccessDeniedException("Invalid Pub/Sub bearer token", e);
        }
    }
}
