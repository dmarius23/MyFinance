package ro.myfinance.settings.application;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import org.springframework.stereotype.Component;
import ro.myfinance.common.messaging.MessageTemplateKind;
import ro.myfinance.common.messaging.TemplateRenderer;

/**
 * Validates a tenant's template before it is stored. The renderer is forgiving by design — it never
 * fails a send — so this is where a mistake has to surface, while the admin is still looking at the
 * editor. A typo'd {@code {{compnayName}}} that got through would reach a client as literal text.
 *
 * <p>A blank subject is legal: it means "keep the built-in subject", letting a firm reword the body
 * only.
 *
 * <p>Uses {@link TemplateRenderer#TOKEN} so the placeholder pattern cannot drift between the
 * validator and the renderer.
 */
@Component
public class TemplateValidator {

    private static final int MAX_BODY = 10_000;
    private static final int MAX_SUBJECT = 300;

    public void validate(MessageTemplateKind kind, String subject, String body) {
        String normalizedBody = TemplateRenderer.normalize(body);
        String normalizedSubject = TemplateRenderer.normalize(subject);

        if (normalizedBody == null || normalizedBody.isBlank()) {
            throw new IllegalArgumentException("Template body must not be empty");
        }
        if (normalizedBody.length() > MAX_BODY) {
            throw new IllegalArgumentException(
                    "Template body must be at most " + MAX_BODY + " characters");
        }

        requireBalanced(normalizedBody);
        requireKnownNames(kind, normalizedBody, kind.allNames());

        // A blank subject means "keep the built-in subject" — legal, skip all subject checks.
        // Blankness must be decided first so that e.g. 301 spaces fails as blank, not as too long.
        if (normalizedSubject != null && !normalizedSubject.isBlank()) {
            if (normalizedSubject.length() > MAX_SUBJECT) {
                throw new IllegalArgumentException(
                        "Template subject must be at most " + MAX_SUBJECT + " characters");
            }
            if (normalizedSubject.contains("\n")) {
                throw new IllegalArgumentException(
                        "Template subject must not contain newlines");
            }
            requireBalanced(normalizedSubject);
            // Subjects take scalars only — a multi-line block in a subject makes no sense.
            // signOff is excluded deliberately: SignOff.names() returns "Sender\nFirm", a
            // multi-line value that would split the Subject: header into continuation lines.
            Set<String> subjectAllowed = new LinkedHashSet<>(kind.placeholders());
            requireKnownNames(kind, normalizedSubject, subjectAllowed);
        }
    }

    private static void requireKnownNames(MessageTemplateKind kind, String text, Set<String> allowed) {
        Matcher m = TemplateRenderer.TOKEN.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException(
                        "Unknown placeholder {{" + name + "}} for " + kind
                                + ". Allowed: " + String.join(", ", new TreeSet<>(allowed)));
            }
        }
    }

    /** Every {@code {{} must be closed, and no stray {@code }}} may remain. */
    private static void requireBalanced(String text) {
        String withoutTokens = TemplateRenderer.TOKEN.matcher(text).replaceAll("");
        if (withoutTokens.contains("{{") || withoutTokens.contains("}}")) {
            throw new IllegalArgumentException(
                    "Unbalanced or malformed placeholder delimiters — every {{name}} must be closed");
        }
    }
}
