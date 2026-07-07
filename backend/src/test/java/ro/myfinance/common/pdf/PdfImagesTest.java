package ro.myfinance.common.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

class PdfImagesTest {

    private static byte[] pdfOf(int pages) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new PDPage());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void rendersEveryPageWhenUnderTheCap() throws Exception {
        List<byte[]> imgs = PdfImages.renderPagesPng(pdfOf(3), 40, 6);
        assertThat(imgs).hasSize(3);
        assertThat(imgs).allSatisfy(b -> assertThat(b).isNotEmpty());
    }

    @Test
    void alwaysIncludesTheLastPageEvenWhenOverTheCap() throws Exception {
        // 5-page invoice, cap 2 → first 2 pages + the last (totals) page = 3 distinct images.
        List<byte[]> imgs = PdfImages.renderPagesPng(pdfOf(5), 40, 2);
        assertThat(imgs).hasSize(3);
    }

    @Test
    void singlePageYieldsOneImage() throws Exception {
        assertThat(PdfImages.renderPagesPng(pdfOf(1), 40, 6)).hasSize(1);
    }

    @Test
    void emptyOnInvalidPdf() {
        assertThat(PdfImages.renderPagesPng(new byte[] {1, 2, 3}, 40, 6)).isEmpty();
    }
}
