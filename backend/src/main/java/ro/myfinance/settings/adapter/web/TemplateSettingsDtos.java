package ro.myfinance.settings.adapter.web;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import ro.myfinance.common.messaging.MessageTemplateKind;
import ro.myfinance.common.messaging.MessageTemplateService;

/** Request/response shapes for the per-tenant message template editor. */
public final class TemplateSettingsDtos {

    private TemplateSettingsDtos() {
    }

    /**
     * One kind as the editor sees it. {@code customized} is false when the text shown is the built-in
     * default, so the UI can offer "Reset to default" only when there is something to reset.
     */
    public record TemplateResponse(String kind, String subject, String body, boolean customized,
                                   List<String> placeholders, List<String> blocks) {

        public static TemplateResponse from(MessageTemplateService.Effective e) {
            // Include signOff in placeholders so the editor chips are driven entirely by the API.
            // The frontend must not restate the contract by appending "signOff" on the client side.
            List<String> placeholders = new java.util.ArrayList<>(e.kind().placeholders());
            placeholders.add(MessageTemplateKind.SIGN_OFF);
            placeholders.sort(null);
            return new TemplateResponse(
                    e.kind().name(),
                    e.subject(),
                    e.body(),
                    e.customized(),
                    java.util.Collections.unmodifiableList(placeholders),
                    e.kind().blocks().stream().sorted().toList());
        }
    }

    public record UpdateTemplateRequest(String subject, @NotBlank String body) {
    }

    /** Preview the submitted, unsaved text against sample data — no company needed. */
    public record PreviewRequest(String subject, @NotBlank String body) {
    }

    public record PreviewResponse(String subject, String body, Map<String, String> sampleValues) {
    }
}
