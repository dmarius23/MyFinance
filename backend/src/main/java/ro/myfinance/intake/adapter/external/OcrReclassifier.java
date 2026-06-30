package ro.myfinance.intake.adapter.external;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ro.myfinance.extraction.adapter.external.ReceiptProperties;
import ro.myfinance.intake.application.DocumentClassifier;
import ro.myfinance.intake.application.DocumentReclassifier;
import ro.myfinance.intake.domain.DocumentType;

/**
 * Recovers text from a non-extractable PDF and re-classifies it. Tiered, mirroring the receipt path:
 * Tesseract first (local, free) and the Anthropic vision API only when Tesseract is unavailable or its
 * output is itself unreadable. Classification of the recovered text stays deterministic.
 */
@Component
public class OcrReclassifier implements DocumentReclassifier {

    private static final Logger log = LoggerFactory.getLogger(OcrReclassifier.class);

    private final DocumentClassifier classifier;
    private final OcrProperties props;
    private final ReceiptProperties anthropic;
    private final RestClient anthropicClient;

    public OcrReclassifier(DocumentClassifier classifier, OcrProperties props,
                           ReceiptProperties anthropic, RestClient.Builder builder) {
        this.classifier = classifier;
        this.props = props;
        this.anthropic = anthropic;
        this.anthropicClient = builder.baseUrl(anthropic.baseUrl())
                .defaultHeader("x-api-key", anthropic.apiKey())
                .defaultHeader("anthropic-version", "2023-06-01")
                .build();
    }

    /**
     * If OCR is enabled and {@code bytes} is a PDF whose text is non-extractable, OCR it and classify the
     * recovered text. Returns the recognised type, or empty (caller keeps UNCLASSIFIED).
     */
    @Override
    public Optional<DocumentType> tryClassify(String contentType, byte[] bytes) {
        if (!props.enabled() || contentType == null || !contentType.toLowerCase().contains("pdf")) {
            return Optional.empty();
        }
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            if (isReadable(new PDFTextStripper().getText(pdf))) {
                return Optional.empty(); // text was fine; the classifier already had its chance
            }
            String text = ocr(pdf);
            if (text.isBlank()) {
                return Optional.empty();
            }
            DocumentType type = classifier.classifyText(text);
            log.info("OCR fallback recovered {} chars → {}", text.length(), type);
            return type == DocumentType.UNCLASSIFIED ? Optional.empty() : Optional.of(type);
        } catch (Exception e) {
            log.warn("OCR reclassification failed", e);
            return Optional.empty();
        }
    }

    /** OCR the first pages: Tesseract, falling back to Anthropic vision when its output is weak. */
    private String ocr(PDDocument pdf) throws Exception {
        PDFRenderer renderer = new PDFRenderer(pdf);
        int pages = Math.min(props.maxPages(), pdf.getNumberOfPages());
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < pages; i++) {
            BufferedImage img = renderer.renderImageWithDPI(i, props.dpi(), ImageType.RGB);
            byte[] png = toPng(img);
            String t = tesseract(png);
            if (!isReadable(t)) {
                t = anthropic(png); // weak/empty Tesseract → vision fallback
            }
            if (t != null && !t.isBlank()) {
                out.append(t).append('\n');
            }
        }
        return out.toString();
    }

    /** Run the local Tesseract CLI; returns "" if the binary is missing or it errors (→ vision fallback). */
    private String tesseract(byte[] png) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("ocr", ".png");
            Files.write(tmp, png);
            Process p = new ProcessBuilder(props.tesseractCmd(), tmp.toString(), "stdout", "-l", props.tesseractLang())
                    .redirectErrorStream(false).start();
            String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            return p.exitValue() == 0 ? text : "";
        } catch (Exception e) {
            log.debug("Tesseract unavailable/failed ({}), will try vision fallback", e.getMessage());
            return "";
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (Exception ignored) { /* temp cleanup */ }
            }
        }
    }

    /** Anthropic vision transcription (only if a key is configured). Plain text out. */
    private String anthropic(byte[] png) {
        if (!anthropic.isAnthropic()) {
            return "";
        }
        try {
            Map<String, Object> body = Map.of(
                    "model", anthropic.model(),
                    "max_tokens", 1024,
                    "temperature", 0,
                    "messages", List.of(Map.of("role", "user", "content", List.of(
                            Map.of("type", "image", "source", Map.of("type", "base64",
                                    "media_type", "image/png", "data", Base64.getEncoder().encodeToString(png))),
                            Map.of("type", "text", "text",
                                    "Transcribe all visible text from this document image as plain text. "
                                            + "Output only the transcription, no commentary.")))));
            JsonNode resp = anthropicClient.post().uri("/v1/messages")
                    .contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().body(JsonNode.class);
            return resp == null ? "" : resp.path("content").path(0).path("text").asText("");
        } catch (RuntimeException e) {
            log.warn("Anthropic OCR fallback failed", e);
            return "";
        }
    }

    /** Heuristic: text is "readable" when a healthy fraction of its characters are actual letters. */
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

    private static byte[] toPng(BufferedImage img) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
