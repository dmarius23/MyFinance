package ro.myfinance.ingestion.application;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import ro.myfinance.intake.domain.DocumentType;

/** Deterministic email metadata hints used before the normal content classifier. */
@Component
public class EmailClassificationRules {

    private static final Set<String> INVOICE = Set.of("factura", "invoice", "e-factura", "efactura");
    private static final Set<String> STATEMENT = Set.of("extras", "statement", "bank statement", "revolut");
    private static final Set<String> DECLARATION = Set.of("d112", "d212", "d300", "d301", "declaratie", "anaf");
    private static final Set<String> PAYROLL = Set.of("payroll", "salar", "salarii", "fluturas");

    public Decision classify(String sender, String subject, String filename) {
        String value = normalize(sender) + " " + normalize(subject) + " " + normalize(filename);
        EnumSet<DocumentType> matches = EnumSet.noneOf(DocumentType.class);
        addIfAny(value, INVOICE, DocumentType.INVOICE, matches);
        addIfAny(value, STATEMENT, DocumentType.BANK_STATEMENT, matches);
        addIfAny(value, DECLARATION, DocumentType.DECLARATION, matches);
        addIfAny(value, PAYROLL, DocumentType.PAYROLL, matches);
        return matches.size() == 1
                ? Decision.forced(matches.iterator().next())
                : matches.isEmpty() ? Decision.none() : Decision.ambiguousDecision();
    }

    private static void addIfAny(String value, Set<String> markers, DocumentType type,
                                 EnumSet<DocumentType> matches) {
        if (markers.stream().anyMatch(value::contains)) {
            matches.add(type);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    public record Decision(DocumentType forcedType, boolean ambiguous) {
        static Decision forced(DocumentType type) { return new Decision(type, false); }
        static Decision none() { return new Decision(null, false); }
        static Decision ambiguousDecision() { return new Decision(null, true); }
    }
}
