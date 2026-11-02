package ro.myfinance.common.messaging;

/**
 * A message ready to send: subject and body, both already rendered from the tenant's template (or the
 * built-in default). The body is still editable by the user before sending — this is the pre-fill.
 */
public record RenderedMessage(String subject, String body) {
}
