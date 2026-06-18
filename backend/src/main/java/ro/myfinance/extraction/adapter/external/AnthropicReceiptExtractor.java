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
            You extract fields from a photographed Romanian fiscal receipt (bon fiscal). It may be \
            crumpled, angled, faded or partly stamped. Return ONLY a JSON object (no markdown, no \
            commentary) with exactly these keys:
            - issuerName: the merchant/company that issued the receipt (e.g. "MEGA IMAGE S.R.L.")
            - issuerCif: the merchant fiscal code (CIF/CUI), e.g. "RO6719278" or "14571643"
            - clientCif: the buyer's fiscal code if present (e.g. "RO20464846"), else null
            - total: the grand total to pay as a number (SUMA TOTALA / TOTAL / TOTAL DE PLATA), dot decimals
            - currency: e.g. "RON"
            - issueDate: the receipt date in ISO format yyyy-MM-dd
            - receiptNumber: the fiscal receipt number (BF / AMEF / RL) if present, else null
            - confidence: your overall confidence 0..1 that these values are correct
            Use null for any field you cannot read confidently.""";

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
    public ParsedReceipt extract(byte[] image, String mediaType) {
        try {
            String b64 = Base64.getEncoder().encodeToString(image);
            Map<String, Object> body = Map.of(
                    "model", model,
                    "max_tokens", 1024,
                    "messages", List.of(Map.of("role", "user", "content", List.of(
                            Map.of("type", "image", "source",
                                    Map.of("type", "base64", "media_type", mediaType, "data", b64)),
                            Map.of("type", "text", "text", PROMPT)))));
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
                    str(n, "issuerName"), str(n, "issuerCif"), str(n, "clientCif"),
                    money(n.get("total")), str(n, "currency"), date(str(n, "issueDate")),
                    str(n, "receiptNumber"), n.path("confidence").asDouble(0.0), "LLM");
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            log.warn("Receipt LLM response was not valid JSON", ex);
            return ParsedReceipt.empty();
        }
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
