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
    void rendersOnlyFirstAndLastPage() throws Exception {
        // A 5-page invoice → header (page 1) + totals (page 5) only; the 3 middle pages are skipped.
        List<byte[]> imgs = PdfImages.renderFirstAndLastPng(pdfOf(5), 40);
        assertThat(imgs).hasSize(2);
        assertThat(imgs).allSatisfy(b -> assertThat(b).isNotEmpty());
    }

    @Test
    void twoPageYieldsBothPages() throws Exception {
        assertThat(PdfImages.renderFirstAndLastPng(pdfOf(2), 40)).hasSize(2);
    }

    @Test
    void singlePageYieldsOneImage() throws Exception {
        assertThat(PdfImages.renderFirstAndLastPng(pdfOf(1), 40)).hasSize(1);
    }

    @Test
    void emptyOnInvalidPdf() {
        assertThat(PdfImages.renderFirstAndLastPng(new byte[] {1, 2, 3}, 40)).isEmpty();
    }
}
