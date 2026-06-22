package ro.myfinance.reports.application;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import javax.imageio.ImageIO;
import ro.myfinance.reports.domain.ReportData;

/**
 * Renders a compact PNG of the headline charts (Venituri/Cheltuieli/Profit bars + an expense-breakdown
 * donut) for the report email attachment. Pure Java2D — no chart library. The interactive charts live in
 * the web modal; this is a lightweight at-a-glance image for inboxes.
 */
public final class ReportChartImage {

    private static final Color TEAL = new Color(20, 184, 166);
    private static final Color GREEN = new Color(33, 140, 115);
    private static final Color RED = new Color(220, 76, 76);
    private static final Color INK = new Color(28, 36, 34);
    private static final Color MUTED = new Color(120, 130, 128);
    private static final Color[] PALETTE = {
            new Color(20, 184, 166), new Color(15, 118, 110), new Color(45, 156, 219),
            new Color(245, 158, 11), new Color(139, 92, 246), new Color(120, 130, 128)
    };

    private ReportChartImage() {
    }

    public static byte[] png(ReportData r) {
        int w = 900;
        int h = 380;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);

        var pl = r.profitLoss();
        BigDecimal expenses = pl.operatingExpenses().add(pl.incomeTax());

        // ---- Left: Venituri / Cheltuieli / Profit bars ----
        g.setColor(INK);
        g.setFont(new Font("SansSerif", Font.BOLD, 16));
        g.drawString(fold("Venituri vs Cheltuieli vs Profit"), 30, 36);
        double max = Math.max(pl.revenue().doubleValue(), Math.max(expenses.doubleValue(), 1));
        int barX = 140;
        int barMax = 230;
        bar(g, "Venituri", pl.revenue(), max, barX, 70, barMax, GREEN);
        bar(g, "Cheltuieli", expenses, max, barX, 120, barMax, RED);
        bar(g, "Profit net", pl.netProfit().max(BigDecimal.ZERO), max, barX, 170, barMax, TEAL);

        // ---- Right: expense-breakdown donut ----
        g.setColor(INK);
        g.setFont(new Font("SansSerif", Font.BOLD, 16));
        g.drawString(fold("Structura cheltuielilor"), 470, 36);
        List<ReportData.Item> items = pl.expenseItems();
        double total = items.stream().mapToDouble(i -> i.amount().doubleValue()).sum();
        int cx = 560;
        int cy = 200;
        int radius = 95;
        double start = 90;
        for (int i = 0; i < items.size(); i++) {
            double frac = total <= 0 ? 0 : items.get(i).amount().doubleValue() / total;
            double extent = -frac * 360;
            g.setColor(PALETTE[i % PALETTE.length]);
            g.fill(new Arc2D.Double(cx - radius, cy - radius, radius * 2, radius * 2, start, extent, Arc2D.PIE));
            start += extent;
        }
        // donut hole
        g.setColor(Color.WHITE);
        g.fillOval(cx - 48, cy - 48, 96, 96);
        // legend
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        int ly = 90;
        for (int i = 0; i < items.size(); i++) {
            g.setColor(PALETTE[i % PALETTE.length]);
            g.fillRect(680, ly - 10, 12, 12);
            g.setColor(INK);
            int pctVal = total <= 0 ? 0 : (int) Math.round(items.get(i).amount().doubleValue() / total * 100);
            g.drawString(shorten(fold(items.get(i).label()), 26) + "  " + pctVal + "%", 698, ly);
            ly += 22;
        }

        g.dispose();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to render charts image", e);
        }
    }

    private static void bar(Graphics2D g, String label, BigDecimal v, double max, int x, int y, int maxW, Color c) {
        g.setColor(MUTED);
        g.setFont(new Font("SansSerif", Font.PLAIN, 13));
        g.drawString(fold(label), 30, y + 14);
        int bw = (int) Math.max(2, v.doubleValue() / max * maxW);
        g.setColor(c);
        g.fillRoundRect(x, y, bw, 22, 6, 6);
        g.setColor(INK);
        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        g.drawString(ReportPdfGenerator.money(v) + " RON", x + bw + 8, y + 16);
    }

    private static String shorten(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private static String fold(String s) {
        return ReportPdfGenerator.fold(s);
    }
}
