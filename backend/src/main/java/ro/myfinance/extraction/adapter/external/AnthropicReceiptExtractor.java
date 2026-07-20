package ro.myfinance.extraction.adapter.external;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import ro.myfinance.extraction.application.ParsedReceipt;

/**
 * Vision-LLM receipt extractor via the Anthropic Messages API (Claude, {@code api.anthropic.com} by
 * default). This is the US-hosted OCR path; for an EU-resident deployment use
 * {@link BedrockReceiptExtractor} instead (see {@code docs/MyFinance-data-privacy-residency-v1.md}).
 * Prompt-building and JSON parsing live in {@link ClaudeReceiptExtractor}; this class only owns the
 * HTTP transport. Never throws — any error yields {@link ParsedReceipt#empty()}.
 */
public class AnthropicReceiptExtractor extends ClaudeReceiptExtractor {

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
    protected String invoke(List<Object> content) {
        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", MAX_TOKENS,
                "temperature", 0, // deterministic extraction — same image(s) yield the same fields
                "messages", List.of(Map.of("role", "user", "content", content)));
        JsonNode resp = client.post().uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        return resp == null ? "" : resp.path("content").path(0).path("text").asText("");
    }
}
