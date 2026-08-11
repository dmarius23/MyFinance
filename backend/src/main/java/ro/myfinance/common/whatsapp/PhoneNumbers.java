package ro.myfinance.common.whatsapp;

/** Normalises phone numbers to E.164 (WhatsApp requires it), assuming a default country for local forms. */
public final class PhoneNumbers {

    private PhoneNumbers() {
    }

    /**
     * Best-effort E.164: strips spaces/punctuation and maps common local forms to {@code +CC…}.
     * <ul>
     *   <li>{@code +40733…} → unchanged</li>
     *   <li>{@code 0040733…} / {@code 0040 733…} → {@code +40733…}</li>
     *   <li>{@code 0733…} (national trunk) → {@code <defaultCountryCode>733…}</li>
     *   <li>{@code 733…} → {@code <defaultCountryCode>733…}</li>
     * </ul>
     * Returns {@code null}/blank inputs unchanged.
     */
    public static String toE164(String phone, String defaultCountryCode) {
        if (phone == null || phone.isBlank()) {
            return phone;
        }
        String cc = defaultCountryCode == null || defaultCountryCode.isBlank() ? "+40" : defaultCountryCode.trim();
        String d = phone.replaceAll("[^0-9+]", "");
        if (d.startsWith("+")) {
            return d;
        }
        if (d.startsWith("00")) {
            return "+" + d.substring(2);
        }
        if (d.startsWith("0")) {
            return cc + d.substring(1);
        }
        return cc + d;
    }
}
