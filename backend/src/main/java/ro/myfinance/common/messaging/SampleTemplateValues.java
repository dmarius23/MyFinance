package ro.myfinance.common.messaging;

import java.util.HashMap;
import java.util.Map;

/**
 * Plausible stand-in values for the template preview, so a firm admin can see their wording rendered
 * without picking a real company — and without any real client data appearing in the Settings screen.
 */
public final class SampleTemplateValues {

    private SampleTemplateValues() {
    }

    public static Map<String, String> forKind(MessageTemplateKind kind) {
        Map<String, String> v = new HashMap<>();
        v.put("companyName", "Exemplu SRL");
        v.put("cui", "RO12345678");
        v.put("month", "Aprilie 2026");
        v.put("treasury", "Trezoreria Cluj Napoca");
        v.put("totalToPay", "1550");
        v.put("revenue", "100 000.00");
        v.put("expenses", "61 000.00");
        v.put("grossProfit", "40 000.00");
        v.put("incomeTax", "1 000.00");
        v.put("netProfit", "39 000.00");
        v.put("taxDeadline", "25 Aprilie 2026");
        v.put("missingCount", "2");
        v.put("signOff", "Maria Popescu\nContaZone SRL");
        v.put("paymentSummary", "TVA: 1250\nImpozite: 300");
        v.put("paymentAccounts", String.join("\n",
                "",
                "- 1250 lei in contul RO11TREZ1112345678901234",
                "- Beneficiar: Trezoreria Cluj Napoca",
                "- CUI Beneficiar: RO12345678",
                "- Explicație: TVA Aprilie-2026",
                "- Scadență: 25 aprilie 2026"));
        v.put("missingTransactions", String.join("\n",
                "• 15.04.2026 — Furnizor Alfa SRL — 1.234,56 RON",
                "• 22.04.2026 — Furnizor Beta SRL — 310,00 RON"));
        return v;
    }
}
