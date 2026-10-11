package ro.myfinance.common.email;

/**
 * The closing block shared by every client-facing message, on both channels (email and WhatsApp).
 *
 * <p>The client is the accounting <em>firm's</em> client, not the individual accountant's and not the
 * product's — so the firm's own name is always the last line. The individual sender is kept above it
 * when known, giving the familiar "person, then company" business signature:
 *
 * <pre>
 * O zi bună,
 * Maria Popescu
 * ContaZone SRL
 * </pre>
 *
 * <p>Blank parts are dropped rather than rendered as placeholders, so a message never goes out with an
 * unfilled "[Numele contabilului]" in it.
 */
public final class SignOff {

    private SignOff() {
    }

    /**
     * @param closing    the closing line, e.g. {@code "O zi bună,"} (rendered as-is)
     * @param senderName the individual sender; omitted when null/blank
     * @param firmName   the accounting firm (tenant) name; omitted when null/blank
     */
    public static String block(String closing, String senderName, String firmName) {
        StringBuilder sb = new StringBuilder(closing);
        appendLine(sb, senderName);
        appendLine(sb, firmName);
        return sb.toString();
    }

    private static void appendLine(StringBuilder sb, String value) {
        if (value != null && !value.isBlank()) {
            sb.append('\n').append(value.trim());
        }
    }
}
