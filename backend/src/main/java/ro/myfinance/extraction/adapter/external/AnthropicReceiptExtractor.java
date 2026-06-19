package ro.myfinance.extraction.adapter.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import ro.myfinance.extraction.application.ParsedReceipt;
import ro.myfinance.extraction.application.ReceiptExtractor;

/**
 * Vision-LLM receipt extractor via the Anthropic Messages API (Claude). Sends the receipt image and
 * asks for a strict JSON object of the matching-relevant fields. Never throws — any error yields
 * {@link ParsedReceipt#empty()} so the receipt degrades to NEEDS_REVIEW.
 */
public class AnthropicReceiptExtractor implements ReceiptExtractor {

    private static final Logger log = LoggerFactory.getLogger(AnthropicReceiptExtractor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PROMPT = """
            You read a photographed Romanian fiscal receipt (bon fiscal), possibly crumpled, angled or \
            faded. Return ONLY a JSON object (no markdown, no commentary) with exactly these keys:
            - issuerName: the merchant company name from the header (e.g. "RIUMED S.R.L.")
            - issuerCif: the MERCHANT fiscal code from the header, labelled "C.I.F." / \
            "Cod Identificare Fiscala" (e.g. "14571643" or "RO6719278")
            - clientCif: the BUYER fiscal code, from a line labelled "CIF CLIENT" / "C.I.F. Client" / \
            "CIF Client" (usually near the total or at the bottom). Read and transcribe this value \
            whenever such a line exists; null only if there is no such line at all.
            - total: the grand total to pay as a number (SUMA TOTALA / TOTAL / TOTAL DE PLATA), dot decimals
            - currency: e.g. "RON"
            - issueDate (yyyy-MM-dd): the date the receipt was ISSUED — the transaction date, usually \
            printed next to a time like "02-06-2026 12:53". IMPORTANT: ignore any year that is part of \
            a street address (e.g. "B-dul 21 Decembrie 1989" is an ADDRESS, not the date).
            - receiptNumber: the fiscal receipt number (BF / AMEF / RL) if present, else null
            - confidence: your overall confidence 0..1
            Transcribe all digits EXACTLY as printed; never invent. Fiscal codes are validated by the \
            system afterwards, so do transcribe the CIF CLIENT value if you can see it. Do not confuse \
            the merchant CIF (header) with the client CIF.""";

    private final RestClient client;
    private final String model;

    public AnthropicReceiptExtractor(ReceiptProperties props, RestClient.Builder builder) {
        this.model = props.model();
        this.client = builder
                .baseUrl(props.baseUrl())
                .defaultHeader("x-api-key", props.apiKey())
                .defaultHeader("anthropic-version", "2023-06-01")
                .build();
    }

    @Override
    public ParsedReceipt extract(byte[] image, String mediaType, String ownCompanyCui) {
        try {
            String b64 = Base64.getEncoder().encodeToString(image);
            String prompt = PROMPT + matchInstruction(ownCompanyCui);
            Map<String, Object> body = Map.of(
                    "model", model,
                    "max_tokens", 1024,
                    "temperature", 0, // deterministic extraction — same image yields the same fields
                    "messages", List.of(Map.of("role", "user", "content", List.of(
                            Map.of("type", "image", "source",
                                    Map.of("type", "base64", "media_type", mediaType, "data", b64)),
                            Map.of("type", "text", "text", prompt)))));
            JsonNode resp = client.post().uri("/v1/messages")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            String text = resp == null ? "" : resp.path("content").path(0).path("text").asText("");
            return parse(text);
        } catch (RuntimeException e) {
            log.warn("Receipt LLM extraction failed", e);
            return ParsedReceipt.empty();
        }
    }

    private ParsedReceipt parse(String text) {
        try {
            int s = text.indexOf('{');
            int e = text.lastIndexOf('}');
            if (s < 0 || e <= s) {
                return ParsedReceipt.empty();
            }
            JsonNode n = MAPPER.readTree(text.substring(s, e + 1));
            return new ParsedReceipt(
                    str(n, "issuerName"), cleanCif(str(n, "issuerCif")), cleanCif(str(n, "clientCif")),
                    money(n.get("total")), str(n, "currency"), date(str(n, "issueDate")),
                    str(n, "receiptNumber"), n.path("confidence").asDouble(0.0), "LLM",
                    bool(n.get("clientMatchesCompany")));
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            log.warn("Receipt LLM response was not valid JSON", ex);
            return ParsedReceipt.empty();
        }
    }

    /** Extra instruction so the model compares the printed CIF CLIENT to the company's own CUI. */
    private static String matchInstruction(String ownCompanyCui) {
        if (ownCompanyCui == null || ownCompanyCui.isBlank()) {
            return "";
        }
        return "\nAlso add a key clientMatchesCompany: true if the CIF CLIENT printed on the receipt is "
                + "the SAME fiscal code as \"" + ownCompanyCui + "\"; false if there is a CIF CLIENT that "
                + "is clearly a DIFFERENT code; null if there is no CIF CLIENT line or you cannot tell. "
                + "Judge this by the overall code, tolerant of a single misread digit.";
    }

    /**
     * Normalize a transcribed fiscal code for display (trim, strip spaces/dots, upper-case) but DO NOT
     * drop it when the control digit fails: a wrong/different-party client CIF is exactly what the
     * accountant needs to see. Wrong-party detection relies on the model's clientMatchesCompany verdict
     * (tolerant of a single misread digit), not on this value.
     */
    private static String cleanCif(String cif) {
        if (cif == null) {
            return null;
        }
        String c = cif.replaceAll("[\\s.]", "").toUpperCase();
        return c.isBlank() ? null : c;
    }

    private static Boolean bool(JsonNode v) {
        return (v == null || v.isNull() || !v.isBoolean()) ? null : v.asBoolean();
    }

    private static String str(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText().trim();
        return s.isEmpty() || s.equalsIgnoreCase("null") ? null : s;
    }

    private static BigDecimal money(JsonNode v) {
        if (v == null || v.isNull()) {
            return null;
        }
        try {
            return new BigDecimal(v.asText().trim().replace(",", "."));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate date(String s) {
        if (s == null) {
            return null;
        }
        try {
            return LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
