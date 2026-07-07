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
            You read a Romanian fiscal document — either a photographed fiscal receipt (bon fiscal) or a \
            supplier invoice (factură). It may be crumpled, angled, faded, or rendered from a PDF. Return \
            ONLY a JSON object (no markdown, no commentary) with exactly these keys:
            - issuerName: the SUPPLIER / seller company name — the party that ISSUED the document \
            (furnizor / vânzător). On a receipt this is the merchant in the header. On an invoice it is \
            the company shown in the logo and/or a "Furnizor" / "Date furnizor" / "Info furnizor" block, \
            which is OFTEN IN THE FOOTER, not at the top. Example: "COMPEXIT TRADING SRL".
            - issuerCif: the SUPPLIER fiscal code — labelled "C.I.F." / "CUI" / \
            "Cod de inregistrare in scopuri de TVA" (e.g. "14571643" or "RO6719278"). It belongs to the \
            SAME party as issuerName (the supplier), not to the buyer.
            - clientCif: the BUYER / client fiscal code — the recipient of the document, usually under a \
            "Firma" / "Client" / "Cumpărător" block near the top, or a "CIF CLIENT" line on a receipt \
            (e.g. "49443957"). Read and transcribe this value whenever it exists; null only if there is \
            no buyer fiscal code at all.
            - total: the GRAND total to pay for the WHOLE invoice as a number (SUMA TOTALA / TOTAL / \
            TOTAL DE PLATA / Val. totala), dot decimals. On a multi-page invoice this final total is on \
            the LAST page — never use a per-page subtotal such as "Sold intermediar".
            - currency: e.g. "RON"
            - issueDate (yyyy-MM-dd): the date the document was ISSUED (Data / data facturii; on a \
            receipt the transaction date next to a time like "02-06-2026 12:53"). IMPORTANT: ignore any \
            year that is part of a street address (e.g. "B-dul 21 Decembrie 1989" is an ADDRESS, not the date).
            - receiptNumber: the receipt or invoice number (BF / AMEF / RL / Nr. factură) if present, else null
            - confidence: your overall confidence 0..1
            Transcribe all digits EXACTLY as printed; never invent. The supplier and the buyer are \
            DIFFERENT parties — do not confuse the supplier CIF with the client CIF. On an invoice the \
            most prominent company block at the top is usually the BUYER; the supplier is identified by \
            the logo and the "Furnizor" details, which may appear only in the footer.""";

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
        return extract(List.of(image), mediaType, ownCompanyCui);
    }

    @Override
    public ParsedReceipt extract(List<byte[]> images, String mediaType, String ownCompanyCui) {
        if (images.isEmpty()) {
            return ParsedReceipt.empty();
        }
        try {
            String prompt = PROMPT + matchInstruction(ownCompanyCui);
            List<Object> content = new java.util.ArrayList<>();
            for (byte[] image : images) {
                content.add(Map.of("type", "image", "source", Map.of(
                        "type", "base64", "media_type", mediaType,
                        "data", Base64.getEncoder().encodeToString(image))));
            }
            content.add(Map.of("type", "text", "text", prompt));
            Map<String, Object> body = Map.of(
                    "model", model,
                    "max_tokens", 1024,
                    "temperature", 0, // deterministic extraction — same image(s) yield the same fields
                    "messages", List.of(Map.of("role", "user", "content", content)));
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

    /**
     * Anchors issuer/buyer on the company's own CUI (it is the buyer, so the supplier is the other
     * party) and asks the model to compare the printed buyer CIF to that CUI.
     */
    private static String matchInstruction(String ownCompanyCui) {
        if (ownCompanyCui == null || ownCompanyCui.isBlank()) {
            return "";
        }
        return "\nThe company with fiscal code \"" + ownCompanyCui + "\" is the BUYER / client of this "
                + "document, NOT the supplier. So the issuer/supplier (issuerName, issuerCif) is the OTHER "
                + "party: never return \"" + ownCompanyCui + "\" or that company's name as the issuer — "
                + "return that code as clientCif instead, and read the supplier's own name and CIF (from "
                + "the logo or the Furnizor block, often the footer). "
                + "Also add a key clientMatchesCompany: true if the buyer fiscal code on the document is "
                + "the SAME as \"" + ownCompanyCui + "\"; false if the buyer code is clearly DIFFERENT; "
                + "null if you cannot tell. Judge by the overall code, tolerant of a single misread digit.";
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
