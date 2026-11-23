package ro.myfinance.common.whatsapp;

import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text rules for Meta template bodies and their positional {@code {{n}}} parameters, shared by the sender
 * (what goes to Meta) and the pre-send preview (what staff see), so the two cannot drift.
 */
public final class MetaTemplateText {

    /** Meta's positional body variable, e.g. {@code {{1}}}. */
    public static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([1-9][0-9]*)\\s*\\}\\}");

    /** Meta caps a body parameter well above this; keep each value readable in a chat bubble. */
    private static final int MAX_PARAMETER_LENGTH = 1000;

    private MetaTemplateText() {
    }

    /**
     * A value made acceptable as a Meta body parameter: Meta rejects parameters containing newlines, tabs
     * or more than four consecutive spaces (#132018), so multi-line blocks (payment lines, missing
     * transactions) are joined with "; " and runs of whitespace collapse to one space. Returns "" for a
     * value with no visible text — callers treat that as missing, since Meta rejects empty parameters.
     */
    public static String parameter(String raw) {
        if (raw == null) {
            return "";
        }
        String joined = String.join("; ", raw.lines().map(String::strip).filter(l -> !l.isEmpty()).toList());
        String collapsed = joined.replaceAll("\\s+", " ").strip();
        return collapsed.length() > MAX_PARAMETER_LENGTH
                ? collapsed.substring(0, MAX_PARAMETER_LENGTH - 1) + "…"
                : collapsed;
    }

    /** Number of distinct positional variables in a Meta body ({@code {{1}} … {{n}}}). */
    public static int variableCount(String body) {
        if (body == null) {
            return 0;
        }
        TreeSet<Integer> positions = new TreeSet<>();
        Matcher m = VARIABLE.matcher(body);
        while (m.find()) {
            positions.add(Integer.parseInt(m.group(1)));
        }
        return positions.size();
    }

    /** The body as the client will read it: {@code {{n}}} replaced by the n-th value (1-based). */
    public static String fill(String body, List<String> values) {
        if (body == null) {
            return null;
        }
        Matcher m = VARIABLE.matcher(body);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            int position = Integer.parseInt(m.group(1));
            String value = position <= values.size() ? values.get(position - 1) : m.group();
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }
}
