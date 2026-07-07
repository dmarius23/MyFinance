package ro.myfinance.common.pdf;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Helpers for PDFs whose embedded fonts have no ToUnicode map (e.g. Apache-FOP / Identity-H), so their
 * text is non-extractable. Used by both the OCR classifier fallback and the invoice extraction fallback
 * to detect such PDFs and render them to an image for vision OCR.
 */
public final class PdfImages {

    private PdfImages() {
    }

    /** Extract the text of the first few pages (empty on failure). */
    public static String extractText(byte[] pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(Math.min(3, Math.max(1, doc.getNumberOfPages())));
            return stripper.getText(doc);
        } catch (Exception e) {
            return "";
        }
    }

    /** True when this PDF's text is genuinely readable (so no OCR is needed). */
    public static boolean isTextReadable(byte[] pdf) {
        return isReadable(extractText(pdf));
    }

    /** Heuristic: a healthy fraction of non-space characters are actual letters. */
    public static boolean isReadable(String text) {
        if (text == null) {
            return false;
        }
        int letters = 0, nonSpace = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) {
                nonSpace++;
                if (Character.isLetter(c)) {
                    letters++;
                }
            }
        }
        return letters >= 20 && nonSpace > 0 && (double) letters / nonSpace > 0.55;
    }

    /**
     * Render pages to PNGs at the given DPI for multi-page vision OCR: the first {@code maxPages} pages
     * plus always the last page (a multi-page invoice's grand total — "Val. totala" — lives on the last
     * page, while page 1 only carries a "Sold intermediar" subtotal). Empty list on failure.
     */
    public static List<byte[]> renderPagesPng(byte[] pdf, int dpi, int maxPages) {
        List<byte[]> out = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            int n = doc.getNumberOfPages();
            if (n == 0) {
                return out;
            }
            Set<Integer> pages = new LinkedHashSet<>();
            for (int i = 0; i < Math.min(n, maxPages); i++) {
                pages.add(i);
            }
            pages.add(n - 1); // the totals page
            PDFRenderer renderer = new PDFRenderer(doc);
            for (int i : pages) {
                BufferedImage img = renderer.renderImageWithDPI(i, dpi, ImageType.RGB);
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                ImageIO.write(img, "png", o);
                out.add(o.toByteArray());
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    /** Render the first page to a PNG at the given DPI; null on failure. */
    public static byte[] renderFirstPagePng(byte[] pdf, int dpi) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            if (doc.getNumberOfPages() == 0) {
                return null;
            }
            BufferedImage img = new PDFRenderer(doc).renderImageWithDPI(0, dpi, ImageType.RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}
