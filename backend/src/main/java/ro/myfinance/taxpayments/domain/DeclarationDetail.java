package ro.myfinance.taxpayments.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The parsed content of one declaration, for an in-app structured preview (ANAF PDFs are XFA dynamic
 * forms that only Adobe renders, so we show the extracted data instead of the raw page).
 */
public record DeclarationDetail(DeclarationType type, String cui, LocalDate period, BigDecimal declaredTotal,
                                BigDecimal computedTotal, boolean mismatch, List<Line> obligations) {

    public record Line(TaxCategory category, String codOblig, BigDecimal amount, LocalDate scadenta, boolean refund) {
    }

    public static DeclarationDetail from(ParsedDeclaration pd) {
        List<Line> lines = pd.obligations().stream()
                .map(o -> new Line(o.category(), o.codOblig(), o.amount(), o.scadenta(), o.refund()))
                .toList();
        return new DeclarationDetail(pd.type(), pd.cui(),
                pd.period() == null ? null : pd.period().atDay(1),
                pd.declaredTotal(), pd.computedTotal(), pd.totalsMismatch(), lines);
    }
}
