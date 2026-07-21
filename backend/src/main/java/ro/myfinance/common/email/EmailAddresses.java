package ro.myfinance.common.email;

/** Email-address helpers. {@link #mask(String)} keeps logs free of full recipient PII. */
final class EmailAddresses {

    private EmailAddresses() {
    }

    /**
     * Mask an address for logging: {@code alexandru.popescu@firma.ro} → {@code a***@firma.ro}. Keeps the
     * first local character and the domain (enough to correlate) but drops the rest of the local part.
     * Null/blank/malformed inputs collapse to a safe placeholder.
     */
    static String mask(String email) {
        if (email == null || email.isBlank()) {
            return "<none>";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
