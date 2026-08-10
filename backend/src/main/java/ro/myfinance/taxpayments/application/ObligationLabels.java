package ro.myfinance.taxpayments.application;

import java.util.Map;

/**
 * Curated short labels for ANAF fiscal-obligation ("creanță fiscală") budget codes, shown next to each
 * amount in the Tax &amp; Payments list — e.g. {@code 628 → "Chirii"}, {@code 604 → "Dividende"}.
 *
 * <p>The <b>code</b> is read from the declaration XML; the <b>label</b> is ours. This is intentionally a
 * small static map — extend it as new codes appear. An unmapped code renders on its own (code only), so
 * nothing breaks when a client uses a code we haven't labelled yet.
 */
public final class ObligationLabels {

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("628", "Chirii"),               // Impozit pe veniturile din cedarea folosinței bunurilor
            Map.entry("604", "Dividende"),            // Impozit pe veniturile din dividende
            Map.entry("103", "Impozit profit"),       // Impozit pe profit
            Map.entry("121", "Impozit micro"),        // Impozit pe veniturile microîntreprinderilor
            Map.entry("602", "Impozit salarii"),      // Impozit pe veniturile din salarii
            Map.entry("412", "CAS"),                  // Contribuția de asigurări sociale
            Map.entry("432", "CASS"),                 // Contribuția de asigurări sociale de sănătate
            Map.entry("480", "CAM"),                  // Contribuția asiguratorie pentru muncă
            Map.entry("TVA", "TVA"));

    private ObligationLabels() {
    }

    /** Short label for a budget code, or {@code null} when the code is not (yet) mapped. */
    public static String label(String cod) {
        return cod == null ? null : LABELS.get(cod.trim());
    }
}
