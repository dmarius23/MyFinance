package ro.myfinance.common.audit;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Masks personal/sensitive values before they are written into an audit entry's before/after JSON, so the
 * audit trail never becomes a second place PII leaks. Masking is by field name: emails are partially masked
 * (like recipient logs), and personal names / phones / IBANs / payroll amounts / credentials are redacted.
 * Business config (statuses, plans, VAT flags, CUI, booleans) is kept — that's the point of the diff.
 */
final class AuditMasking {

    private AuditMasking() {
    }

    static Map<String, Object> mask(Map<String, Object> data) {
        if (data == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        data.forEach((key, value) -> out.put(key, maskValue(key, value)));
        return out;
    }

    private static Object maskValue(String key, Object value) {
        if (value == null || key == null) {
            return value;
        }
        String k = key.toLowerCase(Locale.ROOT);
        if (k.contains("email")) {
            return maskEmail(value.toString());
        }
        if (k.equals("name") || k.contains("fullname") || k.contains("personname") || k.contains("employeename")
                || k.contains("iban") || k.contains("phone") || k.contains("password") || k.contains("secret")
                || k.contains("token") || k.contains("salary") || k.contains("netpay") || k.contains("grosspay")
                || k.contains("payroll")) {
            return "***";
        }
        return value;
    }

    /** {@code alex.pop@firma.ro} → {@code a***@firma.ro}; anything malformed collapses to {@code ***}. */
    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
