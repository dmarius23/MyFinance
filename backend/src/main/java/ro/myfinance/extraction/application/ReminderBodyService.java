package ro.myfinance.extraction.application;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.access.application.EmailEnvelopeService;
import ro.myfinance.common.email.SignOff;
import ro.myfinance.common.i18n.RomanianMonths;
import ro.myfinance.common.messaging.MessageTemplateKind;
import ro.myfinance.common.messaging.MessageTemplateService;
import ro.myfinance.common.messaging.RenderedMessage;
import ro.myfinance.company.adapter.persistence.CompanyRepository;
import ro.myfinance.extraction.application.ReconciliationService.TxnWithMatches;
import ro.myfinance.extraction.domain.BankTransaction;
import ro.myfinance.intake.application.DocumentService;
import ro.myfinance.intake.domain.DocumentType;

/**
 * Picks which of the three document-reminder states applies to a company/period and renders that state's
 * template. This decision used to live in the browser ({@code reminderBody.ts}), which is why the client
 * email and the in-app notification drifted apart; it belongs here, next to the data.
 *
 * <ul>
 *   <li>no bank statement → {@code REMINDER_NOTHING_RECEIVED} (ask for statement + documents)</li>
 *   <li>statement in, transactions still lacking a document → {@code REMINDER_DOCS_MISSING} (list them)</li>
 *   <li>statement in, nothing missing → {@code REMINDER_DOCS_ONLY} (general ask)</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class ReminderBodyService {

    private static final DateTimeFormatter ROW_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final DocumentService documents;
    private final ReconciliationService reconciliation;
    private final EmailEnvelopeService envelopes;
    private final MessageTemplateService templates;
    private final CompanyRepository companies;

    public ReminderBodyService(DocumentService documents, ReconciliationService reconciliation,
                               EmailEnvelopeService envelopes, MessageTemplateService templates,
                               CompanyRepository companies) {
        this.documents = documents;
        this.reconciliation = reconciliation;
        this.envelopes = envelopes;
        this.templates = templates;
        this.companies = companies;
    }

    /** The chosen state plus its rendered subject and body. The body is editable before sending. */
    public record ReminderMessage(MessageTemplateKind state, String subject, String body) {
    }

    public ReminderMessage compose(UUID companyId, LocalDate period) {
        LocalDate month = period.withDayOfMonth(1);
        boolean hasStatement = hasBankStatement(companyId, month);
        List<TxnWithMatches> missing = hasStatement ? missingTransactions(companyId, month) : List.of();
        MessageTemplateKind state = state(hasStatement, missing);

        Map<String, String> values = new HashMap<>();
        values.put("companyName", companyName(companyId));
        values.put("month", RomanianMonths.monthYear(month));
        values.put("missingCount", String.valueOf(missing.size()));
        values.put("missingTransactions", missingBlock(missing));
        values.put("signOff", SignOff.names(envelopes.currentUserName(), envelopes.firmName()));

        RenderedMessage msg = templates.render(state, values);
        return new ReminderMessage(state, msg.subject(), msg.body());
    }

    /** Exposed for the reminder screens, which disable the button for a state the user should not send. */
    public MessageTemplateKind stateFor(UUID companyId, LocalDate period) {
        LocalDate month = period.withDayOfMonth(1);
        boolean hasStatement = hasBankStatement(companyId, month);
        List<TxnWithMatches> missing = hasStatement ? missingTransactions(companyId, month) : List.of();
        return state(hasStatement, missing);
    }

    private static MessageTemplateKind state(boolean hasStatement, List<TxnWithMatches> missing) {
        if (!hasStatement) {
            return MessageTemplateKind.REMINDER_NOTHING_RECEIVED;
        }
        return missing.isEmpty()
                ? MessageTemplateKind.REMINDER_DOCS_ONLY
                : MessageTemplateKind.REMINDER_DOCS_MISSING;
    }

    private boolean hasBankStatement(UUID companyId, LocalDate month) {
        return !documents.listByCompanyPeriodType(companyId, month, DocumentType.BANK_STATEMENT).isEmpty();
    }

    /** A transaction that requires an invoice/receipt and has none matched. */
    private List<TxnWithMatches> missingTransactions(UUID companyId, LocalDate month) {
        return reconciliation.transactionsWithMatches(companyId, month).stream()
                .filter(tw -> tw.txn().isRequiresDocument() && tw.invoices().isEmpty())
                .toList();
    }

    /** One row per transaction: {@code • 15.04.2026 — Acme SRL — 1.234,56 RON}. System-owned format. */
    public String missingBlock(List<TxnWithMatches> missing) {
        List<String> rows = new ArrayList<>();
        for (TxnWithMatches tw : missing) {
            BankTransaction t = tw.txn();
            rows.add("• " + ROW_DATE.format(t.getTxnDate()) + " — " + who(t) + " — "
                    + amount(t.getAmount()) + " RON");
        }
        return String.join("\n", rows);
    }

    private static String who(BankTransaction t) {
        if (t.getPartnerName() != null && !t.getPartnerName().isBlank()) {
            return t.getPartnerName();
        }
        return t.getDescription() != null && !t.getDescription().isBlank() ? t.getDescription() : "—";
    }

    /** Romanian money: dot grouping, comma decimal, always two decimals, absolute value. */
    private static String amount(BigDecimal v) {
        if (v == null) {
            return "0,00";
        }
        DecimalFormatSymbols sym = new DecimalFormatSymbols(java.util.Locale.ROOT);
        sym.setGroupingSeparator('.');
        sym.setDecimalSeparator(',');
        return new DecimalFormat("#,##0.00", sym).format(v.abs());
    }

    /**
     * A tenant template MAY reference {{companyName}} even though no reminder default does, so resolve it
     * — but never fail a compose over a display name.
     */
    private String companyName(UUID companyId) {
        try {
            return companies.findById(companyId)
                    .map(ro.myfinance.company.domain.Company::getLegalName)
                    .orElse("");
        } catch (RuntimeException e) {
            return "";
        }
    }
}
