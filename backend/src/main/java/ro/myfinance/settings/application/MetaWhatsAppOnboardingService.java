package ro.myfinance.settings.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.crypto.SecretCipher;
import ro.myfinance.common.whatsapp.MetaWhatsAppOAuthState;
import ro.myfinance.common.whatsapp.MetaWhatsAppOAuthStateRepository;
import ro.myfinance.common.whatsapp.TenantWhatsAppProvider;
import ro.myfinance.common.whatsapp.TenantWhatsAppProviderRepository;
import ro.myfinance.common.web.ConflictException;
import ro.myfinance.common.web.NotFoundException;

/** Starts Meta Embedded Signup without accepting provider credentials from the browser. */
@Service
@Transactional
public class MetaWhatsAppOnboardingService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration STATE_TTL = Duration.ofMinutes(10);

    private final MetaWhatsAppOAuthStateRepository states;
    private final TenantWhatsAppProviderRepository providers;
    private final MetaWhatsAppOnboardingClient meta;
    private final SecretCipher cipher;
    private final String appId;
    private final String configId;
    private final String redirectUri;
    private final String authUrl;
    private final String scopes;

    public MetaWhatsAppOnboardingService(
            MetaWhatsAppOAuthStateRepository states,
            TenantWhatsAppProviderRepository providers,
            MetaWhatsAppOnboardingClient meta,
            SecretCipher cipher,
            @Value("${myfinance.whatsapp.meta.app-id:}") String appId,
            @Value("${myfinance.whatsapp.meta.config-id:}") String configId,
            @Value("${myfinance.whatsapp.meta.redirect-uri:}") String redirectUri,
            @Value("${myfinance.whatsapp.meta.auth-url:https://www.facebook.com/v24.0/dialog/oauth}")
            String authUrl,
            @Value("${myfinance.whatsapp.meta.scopes:whatsapp_business_management,whatsapp_business_messaging}")
            String scopes) {
        this.states = states;
        this.providers = providers;
        this.meta = meta;
        this.cipher = cipher;
        this.appId = appId;
        this.configId = configId;
        this.redirectUri = redirectUri;
        this.authUrl = authUrl;
        this.scopes = scopes;
    }

    public OnboardingStart start() {
        UUID tenantId = TenantContext.tenantId()
                .orElseThrow(() -> new IllegalStateException("No tenant bound"));
        UUID userId = TenantContext.current()
                .map(TenantContext.Identity::userId)
                .orElseThrow(() -> new IllegalStateException("No user bound"));
        if (appId.isBlank() || configId.isBlank() || redirectUri.isBlank()) {
            throw new ro.myfinance.common.web.MisconfiguredException(
                    "Meta WhatsApp Embedded Signup is not configured");
        }

        String state = randomState();
        Instant expiresAt = Instant.now().plus(STATE_TTL);
        states.save(new MetaWhatsAppOAuthState(tenantId, userId, hash(state), expiresAt));
        String url = UriComponentsBuilder.fromUriString(authUrl)
                .queryParam("client_id", appId)
                .queryParam("config_id", configId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code")
                .queryParam("scope", scopes)
                .queryParam("state", state)
                .build()
                .encode()
                .toUriString();
        return new OnboardingStart(url, expiresAt);
    }

    public TenantWhatsAppProvider complete(String state, String code, String wabaId,
                                          String phoneNumberId, String businessPortfolioId) {
        if (state == null || state.isBlank() || code == null || code.isBlank()) {
            throw new IllegalArgumentException("state and authorization code are required");
        }
        UUID tenantId = TenantContext.tenantId()
                .orElseThrow(() -> new IllegalStateException("No tenant bound"));
        UUID userId = TenantContext.current()
                .map(TenantContext.Identity::userId)
                .orElseThrow(() -> new IllegalStateException("No user bound"));
        MetaWhatsAppOAuthState pending = states.findByStateHash(hash(state))
                .filter(s -> tenantId.equals(s.getTenantId()) && userId.equals(s.getUserId()))
                .orElseThrow(() -> new NotFoundException("Meta WhatsApp onboarding state not found"));
        if (!pending.isUsableAt(Instant.now())) {
            throw new ConflictException("Meta WhatsApp onboarding state is expired or already used");
        }
        if (redirectUri.isBlank()) {
            throw new ro.myfinance.common.web.MisconfiguredException(
                    "Meta WhatsApp redirect URI is not configured");
        }
        String token = meta.exchangeCode(code, redirectUri);
        String waba = trimToNull(wabaId);
        if (waba == null) {
            waba = single(meta.grantedWabaIds(token), "WhatsApp Business Account");
        }
        String ownerBusinessId = meta.validateWaba(token, waba);
        String phone = trimToNull(phoneNumberId);
        if (phone == null) {
            phone = single(meta.phoneNumberIds(token, waba), "WhatsApp phone number");
        }
        meta.validatePhoneNumber(token, phone);
        meta.subscribeToWaba(token, waba);
        if (!cipher.isConfigured()) {
            throw new ro.myfinance.common.web.MisconfiguredException(
                    "Server secret key (MYFINANCE_SECRET_KEY) is not configured — cannot store Meta token");
        }
        TenantWhatsAppProvider provider = providers.findById(tenantId)
                .orElseGet(() -> new TenantWhatsAppProvider(tenantId));
        provider.setMode(TenantWhatsAppProvider.Mode.META_CLOUD_API);
        String portfolio = trimToNull(businessPortfolioId);
        provider.setMetaBusinessPortfolioId(portfolio != null ? portfolio : ownerBusinessId);
        provider.setMetaWabaId(waba);
        provider.setMetaPhoneNumberId(phone);
        provider.setMetaAccessTokenEnc(cipher.encrypt(token));
        pending.consume(Instant.now());
        states.save(pending);
        return providers.save(provider);
    }

    private static String randomState() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String single(java.util.List<String> ids, String asset) {
        if (ids.isEmpty()) {
            throw new ConflictException("Meta did not grant access to a " + asset
                    + " — finish the WhatsApp signup and share one");
        }
        if (ids.size() > 1) {
            throw new ConflictException("Meta granted more than one " + asset
                    + " — reconnect and share exactly one");
        }
        return ids.get(0);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record OnboardingStart(String authorizationUrl, Instant expiresAt) {
    }
}
