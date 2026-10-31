package ro.myfinance.access.application;

/**
 * Builds the self-service "reset your password" email — firm-branded and delivered through the tenant's own
 * email provider, in Romanian (the product's default language).
 *
 * <p>Kept separate from {@link InviteMessageFactory} on purpose: an invitation announces access the
 * recipient did not ask for ("you have been invited by…"), whereas a reset answers something they just
 * requested. Reusing the invitation copy here would tell a long-standing user they had been "invited",
 * and would mean one message trying to serve two very different moments.
 */
final class PasswordResetMessageFactory {

    private PasswordResetMessageFactory() {
    }

    /** Subject line, suffixed with the firm name when known. */
    static String subject(String firmName) {
        String firm = blankToNull(firmName);
        return firm == null
                ? "Resetare parolă — portalul MyFinance"
                : "Resetare parolă — portalul MyFinance (" + firm + ")";
    }

    /**
     * Plain-text body. {@code recipientName} is optional; {@code firmName} falls back to a neutral phrase;
     * {@code link} is the required set-password URL. Closes with the "ignore this if it wasn't you" line
     * that any password-reset mail needs.
     */
    static String body(String firmName, String recipientName, String link) {
        String firm = blankToNull(firmName) == null ? "firma de contabilitate" : firmName.trim();
        String greeting = blankToNull(recipientName) == null
                ? "Bună ziua," : "Bună ziua, " + recipientName.trim() + ",";

        StringBuilder b = new StringBuilder();
        b.append(greeting).append("\n\n");
        b.append("Am primit o solicitare de resetare a parolei pentru contul dvs. din portalul MyFinance.\n\n");
        b.append("Pentru a seta o parolă nouă, deschideți linkul de mai jos:\n");
        b.append(link).append("\n\n");
        b.append("Linkul este valabil o perioadă limitată și poate fi folosit o singură dată. ")
                .append("Dacă nu ați solicitat resetarea parolei, puteți ignora acest mesaj — ")
                .append("parola dvs. actuală rămâne neschimbată.\n\n");
        b.append(ro.myfinance.common.email.SignOff.block("Cu stimă,", null, firm));
        return b.toString();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
