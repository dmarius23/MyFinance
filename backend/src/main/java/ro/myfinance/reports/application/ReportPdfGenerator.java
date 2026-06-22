package ro.myfinance.reports.application;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.format.DateTimeFormatter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;
import ro.myfinance.reports.domain.ReportData;

/**
 * Renders a branded one-page monthly report PDF (PDFBox, no iText) from a {@link ReportData}: header,
 * a Venituri/Cheltuieli/Profit bar, the P&L breakdown, the condensed balance sheet, and KPI figures.
 * Standard-14 fonts can't encode Romanian diacritics, so display text is ASCII-folded (a proper
 * embedded font is a later polish — the figures are unaffected).
 */
@Component
public class ReportPdfGenerator {

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private final PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private final PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    private static final float M = 50;          // left margin
    private static final float TEAL_R = 0.078f, TEAL_G = 0.722f, TEAL_B = 0.651f;

    public byte[] generate(ReportData r) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            float w = page.getMediaBox().getWidth();
            float y = page.getMediaBox().getHeight() - 50;
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                y = header(cs, r, w, y);
                y = plBar(cs, r, y);
                y = profitLoss(cs, r, y);
                y = balanceSheet(cs, r, y);
                kpis(cs, r, y);
            }
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to render report PDF", e);
        }
    }

    private float header(PDPageContentStream cs, ReportData r, float w, float y) throws Exception {
        text(cs, bold, 16, M, y, fold(orEmpty(r.companyName())));
        y -= 16;
        text(cs, regular, 9, M, y, "CUI " + orEmpty(r.cui()));
        text(cs, bold, 13, M, y - 22, "Raport financiar lunar");
        String period = r.periodStart() == null ? "" : DMY.format(r.periodStart()) + " - " + DMY.format(r.periodEnd());
        text(cs, regular, 10, M, y - 38, period);
        if (!r.balanced()) {
            text(cs, bold, 9, M, y - 52, fold("ATENTIE: balanta nu se verifica (debit != credit)"));
        }
        line(cs, M, y - 60, w - M, y - 60);
        return y - 78;
    }

    /** Venituri / Cheltuieli / Profit headline bar. */
    private float plBar(PDPageContentStream cs, ReportData r, float y) throws Exception {
        var pl = r.profitLoss();
        text(cs, bold, 11, M, y, "Venituri vs Cheltuieli vs Profit");
        y -= 18;
        float maxW = 360;
        BigDecimal max = pl.revenue().max(pl.operatingExpenses().add(pl.incomeTax())).max(BigDecimal.ONE);
        y = bar(cs, "Venituri", pl.revenue(), max, maxW, y, 0.13f, 0.55f, 0.45f);
        y = bar(cs, "Cheltuieli", pl.operatingExpenses().add(pl.incomeTax()), max, maxW, y, 0.86f, 0.30f, 0.30f);
        y = bar(cs, "Profit net", pl.netProfit().max(BigDecimal.ZERO), max, maxW, y, TEAL_R, TEAL_G, TEAL_B);
        return y - 6;
    }

    private float bar(PDPageContentStream cs, String label, BigDecimal v, BigDecimal max, float maxW,
                      float y, float cr, float cg, float cb) throws Exception {
        text(cs, regular, 9, M, y + 2, fold(label));
        float bw = (float) (v.doubleValue() / max.doubleValue() * maxW);
        cs.setNonStrokingColor(cr, cg, cb);
        cs.addRect(M + 80, y, Math.max(1, bw), 11);
        cs.fill();
        cs.setNonStrokingColor(0, 0, 0);
        text(cs, bold, 9, M + 90 + Math.max(1, bw), y + 2, money(v) + " RON");
        return y - 18;
    }

    private float profitLoss(PDPageContentStream cs, ReportData r, float y) throws Exception {
        var pl = r.profitLoss();
        text(cs, bold, 11, M, y, "Cont de profit si pierdere");
        y -= 16;
        y = row(cs, "Venituri (cifra de afaceri)", pl.revenue(), y, true);
        for (var it : pl.revenueItems()) {
            y = row(cs, "   " + fold(it.label()), it.amount(), y, false);
        }
        y = row(cs, "Cheltuieli de exploatare", pl.operatingExpenses(), y, true);
        for (var it : pl.expenseItems()) {
            y = row(cs, "   " + fold(it.label()), it.amount(), y, false);
        }
        y -= 2;
        y = row(cs, "Profit brut", pl.grossProfit(), y, true);
        y = row(cs, "Impozit pe venit/profit", pl.incomeTax(), y, false);
        y = row(cs, "Profit net", pl.netProfit(), y, true);
        return y - 10;
    }

    private float balanceSheet(PDPageContentStream cs, ReportData r, float y) throws Exception {
        var bs = r.balanceSheet();
        text(cs, bold, 11, M, y, "Pozitie financiara (solduri finale)");
        y -= 16;
        y = row(cs, "ACTIVE", bs.totalAssets(), y, true);
        for (var it : bs.assets()) {
            y = row(cs, "   " + fold(it.label()), it.amount(), y, false);
        }
        y = row(cs, "DATORII", bs.totalLiabilities(), y, true);
        for (var it : bs.liabilities()) {
            y = row(cs, "   " + fold(it.label()), it.amount(), y, false);
        }
        y = row(cs, "CAPITALURI PROPRII", bs.totalEquity(), y, true);
        for (var it : bs.equity()) {
            y = row(cs, "   " + fold(it.label()), it.amount(), y, false);
        }
        return y - 10;
    }

    private void kpis(PDPageContentStream cs, ReportData r, float y) throws Exception {
        var k = r.kpis();
        text(cs, bold, 11, M, y, "Indicatori cheie");
        y -= 16;
        text(cs, regular, 9, M, y, fold("Marja bruta: " + pct(k.grossMargin())
                + "   |   Marja neta: " + pct(k.netMargin())
                + "   |   Lichiditate curenta: " + num(k.currentRatio())
                + "   |   Grad de indatorare: " + num(k.debtToEquity())));
    }

    // ---- low-level helpers ----

    private float row(PDPageContentStream cs, String label, BigDecimal amount, float y, boolean strong) throws Exception {
        PDType1Font f = strong ? bold : regular;
        text(cs, f, strong ? 10 : 9, M, y, label);
        String amt = money(amount) + " RON";
        float aw = f.getStringWidth(amt) / 1000 * (strong ? 10 : 9);
        text(cs, f, strong ? 10 : 9, 545 - aw, y, amt);
        return y - (strong ? 15 : 12);
    }

    private void text(PDPageContentStream cs, PDType1Font font, float size, float x, float y, String s) throws Exception {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(s == null ? "" : s);
        cs.endText();
    }

    private void line(PDPageContentStream cs, float x1, float y1, float x2, float y2) throws Exception {
        cs.setStrokingColor(0.85f, 0.87f, 0.86f);
        cs.moveTo(x1, y1);
        cs.lineTo(x2, y2);
        cs.stroke();
        cs.setStrokingColor(0, 0, 0);
    }

    static String money(BigDecimal v) {
        if (v == null) return "0.00";
        java.text.DecimalFormatSymbols sym = new java.text.DecimalFormatSymbols();
        sym.setGroupingSeparator(' ');
        sym.setDecimalSeparator('.');
        return new java.text.DecimalFormat("#,##0.00", sym).format(v);
    }

    private static String pct(BigDecimal v) {
        return v == null ? "-" : v + "%";
    }

    private static String num(BigDecimal v) {
        return v == null ? "-" : v.toPlainString();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /** ASCII-fold so Standard-14 fonts can render RO text (ș→s, ț→t, ă→a, î→i, â→a). */
    static String fold(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.replace('ș', 's').replace('ț', 't')
                .replace('Ș', 'S').replace('Ț', 'T');
    }
}
