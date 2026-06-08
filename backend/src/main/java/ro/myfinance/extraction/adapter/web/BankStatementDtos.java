package ro.myfinance.extraction.adapter.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import ro.myfinance.extraction.domain.BankStatement;
import ro.myfinance.extraction.domain.BankTransaction;

public final class BankStatementDtos {

    private BankStatementDtos() {
    }

    public record StatementResponse(UUID id, String bankCode, String accountIban,
                                    BigDecimal openingBalance, BigDecimal closingBalance,
                                    String status, boolean crossCheckOk, int txnCount) {
        public static StatementResponse from(BankStatement s) {
            return new StatementResponse(s.getId(), s.getBankCode(), s.getAccountIban(),
                    s.getOpeningBalance(), s.getClosingBalance(), s.getStatus().name(),
                    s.isCrossCheckOk(), s.getTxnCount());
        }
    }

    public record MatchedInvoiceResponse(UUID invoiceId, UUID documentId, String filename,
                                         BigDecimal totalAmount, LocalDate invoiceDate, String supplierName) {
    }

    public record InvoiceResponse(UUID id, UUID documentId, String filename, String supplierName,
                                  String supplierIban, BigDecimal totalAmount, LocalDate invoiceDate,
                                  String status) {
        public static InvoiceResponse from(ro.myfinance.extraction.domain.Invoice i) {
            return new InvoiceResponse(i.getId(), i.getDocumentId(), i.getOriginalFilename(),
                    i.getSupplierName(), i.getSupplierIban(), i.getTotalAmount(), i.getInvoiceDate(), i.getStatus());
        }
    }

    public record MatchRequest(UUID invoiceId) {
    }

    public record TransactionResponse(UUID id, UUID statementId, LocalDate txnDate, BigDecimal amount,
                                      String direction, String partnerName, String partnerIban,
                                      String description, BigDecimal balanceAfter, boolean requiresDocument,
                                      boolean matched, String category, String decisionSource, String reason,
                                      java.util.List<MatchedInvoiceResponse> matchedInvoices) {
        public static TransactionResponse from(ro.myfinance.extraction.application.ReconciliationService.TxnWithMatches tw) {
            BankTransaction t = tw.txn();
            java.util.List<MatchedInvoiceResponse> mi = tw.invoices().stream()
                    .map(v -> new MatchedInvoiceResponse(v.invoiceId(), v.documentId(), v.filename(),
                            v.totalAmount(), v.invoiceDate(), v.supplierName()))
                    .toList();
            return new TransactionResponse(t.getId(), t.getStatementId(), t.getTxnDate(), t.getAmount(),
                    t.getDirection().name(), t.getPartnerName(), t.getPartnerIban(), t.getDescription(),
                    t.getBalanceAfter(), t.isRequiresDocument(), !mi.isEmpty(),
                    t.getCategory() == null ? null : t.getCategory().name(),
                    t.getDecisionSource() == null ? null : t.getDecisionSource().name(), reason(t), mi);
        }

        private static String reason(BankTransaction t) {
            if (t.getDecisionSource() == ro.myfinance.extraction.domain.DecisionSource.ACCOUNTANT_SET) {
                return t.getOverrideReason() != null ? t.getOverrideReason()
                        : "Set by accountant — learned rule saved";
            }
            if (t.getDecisionSource() == ro.myfinance.extraction.domain.DecisionSource.LEARNED_RULE) {
                return "Remembered from a past decision";
            }
            if (t.getCategory() == null) {
                return "";
            }
            return switch (t.getCategory()) {
                case INCOME -> "Incoming receipt — no purchase document";
                case TAX -> "Paid to Treasury — covered by declaration";
                case OWN_TRANSFER -> "Transfer between own accounts — no document";
                case SALARY -> "Covered by payroll / D112";
                case FEE -> "Bank/processor fee — no document";
                case LEASING -> "Leasing invoice / schedule required";
                case SUPPLIER -> "Supplier purchase — invoice required";
            };
        }
    }

    public record SetRequirementRequest(boolean requiresDocument, String reason) {
    }
}
