package ro.myfinance.extraction.application;

import static ro.myfinance.extraction.application.ReconThresholds.DATE_BACK_TOLERANCE_DAYS;
import static ro.myfinance.extraction.application.ReconThresholds.TOLERANCE;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.extraction.application.ReconciliationService.MatchSuggestion;
import ro.myfinance.extraction.application.ReconciliationService.OpenInvoiceView;
import ro.myfinance.extraction.application.ReconciliationService.OpenTxnView;
import ro.myfinance.extraction.application.ReconciliationService.SuggestionLink;

/**
 * One-click match proposals for the period an accountant is reconciling. Exact 1:1 in-period matches are
 * already auto-applied, so this surfaces what {@link TransactionMatcher} can't decide alone: cross-period
 * exacts, one payment settling several invoices (SPLIT), and one invoice cleared by several payments
 * (INSTALLMENT). Works off the open-item lists from {@link ReconciliationView}; proposes only, never writes.
 */
@Service
public class MatchSuggester {

    private final ReconciliationView view;

    public MatchSuggester(ReconciliationView view) {
        this.view = view;
    }

    /**
     * All grouped by supplier IBAN, date-guarded, and non-overlapping (each open txn/invoice appears in
     * at most one suggestion).
     */
    @Transactional(readOnly = true)
    public List<MatchSuggestion> suggestions(UUID companyId, java.time.LocalDate periodMonth) {
        List<OpenTxnView> openTxns = view.openTransactions(companyId, periodMonth, 0); // current period only
        List<OpenInvoiceView> openInvs = view.openInvoices(companyId, periodMonth, 18); // rolling window

        java.util.Map<String, List<OpenTxnView>> txnsByIban = new java.util.HashMap<>();
        for (OpenTxnView t : openTxns) {
            if (t.partnerIban() != null) {
                txnsByIban.computeIfAbsent(t.partnerIban(), k -> new java.util.ArrayList<>()).add(t);
            }
        }
        java.util.Map<String, List<OpenInvoiceView>> invsByIban = new java.util.HashMap<>();
        for (OpenInvoiceView i : openInvs) {
            // Never suggest a wrong-party invoice/receipt (client CIF ≠ this company) for matching.
            if (i.supplierIban() != null && i.remaining() != null && !Boolean.TRUE.equals(i.wrongParty())) {
                invsByIban.computeIfAbsent(i.supplierIban(), k -> new java.util.ArrayList<>()).add(i);
            }
        }

        java.util.Set<UUID> usedTxns = new java.util.HashSet<>();
        java.util.Set<UUID> usedInvs = new java.util.HashSet<>();
        List<MatchSuggestion> out = new java.util.ArrayList<>();

        for (String iban : txnsByIban.keySet()) {
            List<OpenInvoiceView> invs = invsByIban.getOrDefault(iban, List.of());
            if (invs.isEmpty()) {
                continue;
            }
            List<OpenTxnView> txns = txnsByIban.get(iban);

            // 1) EXACT cross-period: a payment whose remaining equals a single open invoice's remaining.
            for (OpenTxnView t : txns) {
                if (usedTxns.contains(t.id())) {
                    continue;
                }
                for (OpenInvoiceView i : invs) {
                    if (usedInvs.contains(i.invoiceId()) || !dateOk(t, i)) {
                        continue;
                    }
                    if (i.remaining().subtract(t.remaining()).abs().compareTo(TOLERANCE) <= 0) {
                        out.add(new MatchSuggestion("EXACT", List.of(link(t, i, i.remaining()))));
                        usedTxns.add(t.id());
                        usedInvs.add(i.invoiceId());
                        break;
                    }
                }
            }

            // 2) SPLIT: one payment settles a subset of open invoices summing to its remaining.
            for (OpenTxnView t : txns) {
                if (usedTxns.contains(t.id())) {
                    continue;
                }
                List<OpenInvoiceView> cand = invs.stream()
                        .filter(i -> !usedInvs.contains(i.invoiceId()) && dateOk(t, i)).toList();
                List<Integer> subset = subsetSum(cand.stream().map(OpenInvoiceView::remaining).toList(), t.remaining());
                if (subset != null) {
                    List<SuggestionLink> links = new java.util.ArrayList<>();
                    for (int idx : subset) {
                        OpenInvoiceView i = cand.get(idx);
                        links.add(link(t, i, i.remaining()));
                        usedInvs.add(i.invoiceId());
                    }
                    usedTxns.add(t.id());
                    out.add(new MatchSuggestion("SPLIT", links));
                }
            }

            // 3) INSTALLMENT: one invoice cleared by a subset of this period's payments.
            for (OpenInvoiceView i : invs) {
                if (usedInvs.contains(i.invoiceId())) {
                    continue;
                }
                List<OpenTxnView> cand = txns.stream()
                        .filter(t -> !usedTxns.contains(t.id()) && dateOk(t, i)).toList();
                List<Integer> subset = subsetSum(cand.stream().map(OpenTxnView::remaining).toList(), i.remaining());
                if (subset != null) {
                    List<SuggestionLink> links = new java.util.ArrayList<>();
                    for (int idx : subset) {
                        OpenTxnView t = cand.get(idx);
                        links.add(link(t, i, t.remaining()));
                        usedTxns.add(t.id());
                    }
                    usedInvs.add(i.invoiceId());
                    out.add(new MatchSuggestion("INSTALLMENT", links));
                }
            }
        }
        return out;
    }

    private SuggestionLink link(OpenTxnView t, OpenInvoiceView i, BigDecimal amount) {
        return new SuggestionLink(t.id(), t.txnDate(), t.partnerName(), t.amount(),
                i.invoiceId(), i.filename(), i.supplierName(), amount);
    }

    private boolean dateOk(OpenTxnView t, OpenInvoiceView i) {
        return i.invoiceDate() == null
                || !t.txnDate().isBefore(i.invoiceDate().minusDays(DATE_BACK_TOLERANCE_DAYS));
    }

    /**
     * First subset (size 2..4) of {@code vals} summing to {@code target} within tolerance, as indices.
     * Bounded brute force over up to 16 candidates; returns null when none (or too many candidates).
     */
    private List<Integer> subsetSum(List<BigDecimal> vals, BigDecimal target) {
        int n = vals.size();
        if (n < 2 || n > 16) {
            return null;
        }
        for (int mask = 1; mask < (1 << n); mask++) {
            int bits = Integer.bitCount(mask);
            if (bits < 2 || bits > 4) {
                continue;
            }
            BigDecimal sum = BigDecimal.ZERO;
            for (int j = 0; j < n; j++) {
                if ((mask & (1 << j)) != 0) {
                    sum = sum.add(vals.get(j));
                }
            }
            if (sum.subtract(target).abs().compareTo(TOLERANCE) <= 0) {
                List<Integer> idx = new java.util.ArrayList<>();
                for (int j = 0; j < n; j++) {
                    if ((mask & (1 << j)) != 0) {
                        idx.add(j);
                    }
                }
                return idx;
            }
        }
        return null;
    }
}
