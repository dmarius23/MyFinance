package ro.myfinance.common.messaging;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The client-facing messages a tenant may reword, each declaring the placeholder names its template may
 * use. {@code placeholders} are scalars (a company name, a month, a figure); {@code blocks} are
 * multi-line lists the application renders — a tenant positions a block but never formats its rows.
 *
 * <p>{@code signOff} is allowed everywhere: it renders the sender and firm name lines, so a firm can
 * never hardcode the wrong firm name. The closing line above it ("O zi bună,") is ordinary prose the
 * tenant owns.
 *
 * <p>Saves are validated against {@link #allNames()}, so a typo like {@code {{compnayName}}} fails in
 * Settings instead of reaching a client as literal text.
 */
public enum MessageTemplateKind {

    TAX(Set.of("companyName", "cui", "month", "treasury", "totalToPay"),
            Set.of("paymentSummary", "paymentAccounts")),

    REPORT(Set.of("companyName", "cui", "month", "revenue", "expenses", "grossProfit", "incomeTax",
            "netProfit"), Set.of()),

    PAYROLL(Set.of("companyName", "month", "taxDeadline"), Set.of()),

    REMINDER_NOTHING_RECEIVED(Set.of("companyName", "month"), Set.of()),

    REMINDER_DOCS_MISSING(Set.of("companyName", "month", "missingCount"),
            Set.of("missingTransactions")),

    REMINDER_DOCS_ONLY(Set.of("companyName", "month"), Set.of());

    /** Available to every kind — the sender + firm name lines. */
    public static final String SIGN_OFF = "signOff";

    private final Set<String> placeholders;
    private final Set<String> blocks;

    MessageTemplateKind(Set<String> placeholders, Set<String> blocks) {
        this.placeholders = Set.copyOf(placeholders);
        this.blocks = Set.copyOf(blocks);
    }

    public Set<String> placeholders() {
        return placeholders;
    }

    public Set<String> blocks() {
        return blocks;
    }

    /** Every name a template of this kind may reference, including {@link #SIGN_OFF}. */
    public Set<String> allNames() {
        Set<String> all = new LinkedHashSet<>(placeholders);
        all.addAll(blocks);
        all.add(SIGN_OFF);
        return Set.copyOf(all);
    }

    /** True when this kind is one of the three document-reminder states. */
    public boolean isReminder() {
        return this == REMINDER_NOTHING_RECEIVED || this == REMINDER_DOCS_MISSING
                || this == REMINDER_DOCS_ONLY;
    }
}
