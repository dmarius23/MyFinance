package ro.myfinance.common.web;

/**
 * 503 — the request is valid but the <b>server</b> is missing configuration needed to serve it (e.g.
 * {@code MYFINANCE_SECRET_KEY} unset, so per-tenant provider secrets cannot be encrypted).
 *
 * <p>Distinct from {@link ConflictException}: that means the <em>tenant</em> must change something they
 * control from the UI; this means an operator must fix deployment config. The message is surfaced to the
 * client on purpose — it names the missing setting so the problem is self-diagnosing instead of arriving
 * as an opaque 500. Never put a secret's <em>value</em> in the message, only its name.
 */
public class MisconfiguredException extends RuntimeException {
    public MisconfiguredException(String message) {
        super(message);
    }
}
