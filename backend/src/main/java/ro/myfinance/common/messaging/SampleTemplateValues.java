package ro.myfinance.common.messaging;

import java.util.HashMap;
import java.util.Map;

/**
 * Plausible stand-in values for the template preview, so a firm admin can see their wording rendered
 * without picking a real company — and without any real client data appearing in the Settings screen.
 *
 * <p>{@link #forKind} returns only the entries that the given kind actually declares via
 * {@link MessageTemplateKind#allNames()}, so the preview response's {@code sampleValues} map
 * advertises exactly which fields apply to the template being edited — no more, no less.
 */
public final class SampleTemplateValues {

    private SampleTemplateValues() {
    }

    /**
     * The full superset of all possible placeholder values. Kept private so callers always go through
     * {@link #forKind} and receive only the names their kind declares.
     */
    private static final Map<String, String> ALL = new HashMap<>();

    static {
        ALL.put("companyName", "Exemplu SRL");
        ALL.put("cui", "RO12345678");
        ALL.put("month", "Aprilie 2026");
        ALL.put("treasury", "Trezoreria Cluj Napoca");
        ALL.put("totalToPay", "1550");
        ALL.put("revenue", "100 000.00");
        ALL.put("expenses", "61 000.00");
        ALL.put("grossProfit", "40 000.00");
        ALL.put("incomeTax", "1 000.00");
        ALL.put("netProfit", "39 000.00");
        ALL.put("taxDeadline", "25 Aprilie 2026");
        ALL.put("missingCount", "2");
        ALL.put("signOff", "Maria Popescu\nContaZone SRL");
        ALL.put("paymentSummary", "TVA: 1250\nImpozite: 300");
        ALL.put("paymentAccounts", String.join("\n",
                "",
                "- 1250 lei in contul RO11TREZ1112345678901234",
                "- Beneficiar: Trezoreria Cluj Napoca",
                "- CUI Beneficiar: RO12345678",
                "- Explicație: TVA Aprilie-2026",
                "- Scadență: 25 aprilie 2026"));
        ALL.put("missingTransactions", String.join("\n",
                "• 15.04.2026 — Furnizor Alfa SRL — 1.234,56 RON",
                "• 22.04.2026 — Furnizor Beta SRL — 310,00 RON"));
    }

    /**
     * Returns sample values scoped to the names {@code kind} actually declares — placeholders, blocks,
     * and {@code signOff}. The UI can then show exactly which fields apply to the template being edited.
     */
    public static Map<String, String> forKind(MessageTemplateKind kind) {
        Map<String, String> result = new HashMap<>();
        for (String name : kind.allNames()) {
            String value = ALL.get(name);
            if (value != null) {
                result.put(name, value);
            }
        }
        return result;
    }
}
