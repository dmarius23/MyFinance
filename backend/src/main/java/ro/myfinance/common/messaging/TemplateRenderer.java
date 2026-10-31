package ro.myfinance.common.messaging;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Substitutes {@code {{name}}} placeholders in a tenant's message template.
 *
 * <p>Deliberately not a template language: no conditionals, no loops, no expressions. Computed lists
 * arrive already rendered as multi-line block strings, so there is nothing for a tenant to get wrong
 * beyond their own prose.
 *
 * <p>Three rules matter:
 * <ol>
 *   <li><b>Single pass.</b> Substituted values are never re-scanned, so a company name containing
 *       {@code {{…}}} is inserted literally rather than treated as a placeholder.</li>
 *   <li><b>Empty-line elision.</b> A line whose literal text is blank and whose every placeholder
 *       resolved to empty is dropped. This keeps {@code "O zi bună,\n{{signOff}}"} correct when both
 *       sender and firm are unknown, and stops an empty block leaving a stray blank line.</li>
 *   <li><b>Unknown names are stripped.</b> Saves are validated against
 *       {@link MessageTemplateKind#allNames()}, so this only catches a row written before a kind's
 *       contract changed — defence in depth, so no client ever sees literal {@code {{oldName}}}.</li>
 * </ol>
 *
 * <p>Never throws on content. Malformed delimiters are rejected at save time, and anything that slips
 * through is left as-is rather than failing a send.
 */
@Component
public class TemplateRenderer {

    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9_]*)\\s*}}");

    public String render(String template, Map<String, String> values) {
        if (template == null) {
            return "";
        }
        List<String> out = new ArrayList<>();
        for (String line : normalize(template).split("\n", -1)) {
            Line rendered = renderLine(line, values);
            if (rendered.elide()) {
                continue;
            }
            out.add(rendered.text());
        }
        return String.join("\n", out);
    }

    /** Collapse CRLF/CR so a template pasted from Word cannot put stray \r into a client's email. */
    public static String normalize(String text) {
        return text == null ? null : text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private record Line(String text, boolean elide) {
    }

    private Line renderLine(String line, Map<String, String> values) {
        Matcher m = TOKEN.matcher(line);
        StringBuilder rendered = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        boolean sawToken = false;
        boolean allTokensEmpty = true;
        int cursor = 0;
        while (m.find()) {
            sawToken = true;
            rendered.append(line, cursor, m.start());
            literal.append(line, cursor, m.start());
            String value = values.get(m.group(1));
            if (value != null && !value.isEmpty()) {
                allTokensEmpty = false;
                rendered.append(value);
            }
            cursor = m.end();
        }
        rendered.append(line.substring(cursor));
        literal.append(line.substring(cursor));
        boolean elide = sawToken && allTokensEmpty && literal.toString().isBlank();
        return new Line(rendered.toString(), elide);
    }
}
