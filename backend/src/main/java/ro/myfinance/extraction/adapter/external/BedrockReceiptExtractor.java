package ro.myfinance.extraction.adapter.external;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;
import ro.myfinance.extraction.application.ParsedReceipt;

/**
 * EU-resident vision-LLM receipt extractor via AWS Bedrock (Claude in an EU region such as
 * {@code eu-central-1} / {@code eu-west-1}). This keeps document images inside the EU — the privacy
 * path required for production (see {@code docs/MyFinance-data-privacy-residency-v1.md}); the
 * Anthropic-API adapter posts to a US endpoint.
 *
 * <p>Prompt-building and JSON parsing are shared via {@link ClaudeReceiptExtractor}; this class owns the
 * Bedrock transport only. The Bedrock request body uses {@code anthropic_version: "bedrock-2023-05-31"}
 * and carries NO {@code model} field — the model (or EU inference-profile id) is the request's
 * {@code modelId}. Credentials and region come from the AWS default provider chain (an instance role in
 * production); {@code myfinance.receipt.region} overrides the region when set. Never throws — any error
 * yields {@link ParsedReceipt#empty()}.
 */
public class BedrockReceiptExtractor extends ClaudeReceiptExtractor {

    private final BedrockRuntimeClient client;
    private final String modelId;

    public BedrockReceiptExtractor(ReceiptProperties props) {
        this.modelId = props.model();
        var b = BedrockRuntimeClient.builder();
        if (props.region() != null && !props.region().isBlank()) {
            b.region(Region.of(props.region()));
        }
        this.client = b.build();
    }

    @Override
    protected String invoke(List<Object> content) {
        try {
            // No "model" field: on Bedrock the model/inference-profile id is the request's modelId.
            Map<String, Object> body = Map.of(
                    "anthropic_version", "bedrock-2023-05-31",
                    "max_tokens", MAX_TOKENS,
                    "temperature", 0, // deterministic extraction — same image(s) yield the same fields
                    "messages", List.of(Map.of("role", "user", "content", content)));
            String json = MAPPER.writeValueAsString(body);
            InvokeModelResponse resp = client.invokeModel(InvokeModelRequest.builder()
                    .modelId(modelId)
                    .contentType("application/json")
                    .accept("application/json")
                    .body(SdkBytes.fromUtf8String(json))
                    .build());
            JsonNode root = MAPPER.readTree(resp.body().asUtf8String());
            return root.path("content").path(0).path("text").asText("");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Bedrock request/response JSON error", e);
        }
    }
}
