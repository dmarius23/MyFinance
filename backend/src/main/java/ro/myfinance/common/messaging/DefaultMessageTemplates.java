package ro.myfinance.common.messaging;

/**
 * The built-in wording for every message kind — the text clients receive until their accounting firm
 * edits it. Kept in code rather than seeded into the database so it stays one source of truth: a firm
 * that never customizes has no row, "reset to default" is a DELETE, and improving a default improves it
 * for every such firm at once.
 *
 * <p>Changing any string here changes what real clients receive. {@code DefaultMessageTemplatesTest}
 * pins the tax, report and payroll bodies against the pre-template wording byte for byte.
 */
public final class DefaultMessageTemplates {

    public record Template(String subject, String body) {
    }

    private DefaultMessageTemplates() {
    }

    private static final Template TAX = new Template("Sume de plată — {{month}}", """
            Bună ziua,

            Sumele de plată pentru luna {{month}} aferente firmei {{companyName}} sunt următoarele:

            {{paymentSummary}}
            Conturi:
            {{paymentAccounts}}

            O zi plăcută,
            {{signOff}}
            """);

    private static final Template REPORT = new Template("Raport financiar — {{month}}", """
            Bună ziua,

            Vă transmitem atașat raportul financiar pentru luna {{month}}.

            Pe scurt:
            • Venituri: {{revenue}} RON
            • Cheltuieli: {{expenses}} RON
            • Profit net: {{netProfit}} RON

            Detaliile complete și graficele se găsesc în documentele atașate.

            O zi bună,
            {{signOff}}""");

    private static final Template PAYROLL = new Template("State de plată — {{month}}", """
            Bună ziua,

            Vă rog frumos să regăsiți atașate statul de plată, fluturașul de salariu și pontajul pentru luna {{month}}.

            Restul de plată de pe statul de plată și fluturaș trebuie virat din contul firmei în contul personal al salariatului.

            Diferența dintre restul de plată și salariul brut reprezintă taxele salariale. Acestea se vor plăti până în data de {{taxDeadline}}.

            În ceea ce privește sumele care se plătesc către Trezorerie, precum și conturile aferente, vom reveni cu un email separat.

            O zi bună,
            {{signOff}}""");

    private static final String REMINDER_SUBJECT = "Documente lipsă — {{month}}";

    private static final Template REMINDER_NOTHING_RECEIVED = new Template(REMINDER_SUBJECT, """
            Bună ziua,

            Pentru luna {{month}} mai avem nevoie de extrasul de cont și de facturile / chitanțele aferente.

            Vă rugăm să le încărcați în portal.

            Mulțumim,
            {{signOff}}""");

    private static final Template REMINDER_DOCS_MISSING = new Template(REMINDER_SUBJECT, """
            Bună ziua,

            Pentru luna {{month}} mai avem nevoie de documentele justificative (factură/chitanță) pentru următoarele tranzacții:
            {{missingTransactions}}

            Vă rugăm să le încărcați în portal.

            Mulțumim,
            {{signOff}}""");

    private static final Template REMINDER_DOCS_ONLY = new Template(REMINDER_SUBJECT, """
            Bună ziua,

            Pentru luna {{month}} mai avem nevoie de facturile / chitanțele aferente.

            Vă rugăm să le încărcați în portal.

            Mulțumim,
            {{signOff}}""");

    public static Template of(MessageTemplateKind kind) {
        return switch (kind) {
            case TAX -> TAX;
            case REPORT -> REPORT;
            case PAYROLL -> PAYROLL;
            case REMINDER_NOTHING_RECEIVED -> REMINDER_NOTHING_RECEIVED;
            case REMINDER_DOCS_MISSING -> REMINDER_DOCS_MISSING;
            case REMINDER_DOCS_ONLY -> REMINDER_DOCS_ONLY;
        };
    }
}
