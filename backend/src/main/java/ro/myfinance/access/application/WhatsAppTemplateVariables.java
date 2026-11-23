package ro.myfinance.access.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import ro.myfinance.common.email.EmailKind;
import ro.myfinance.common.messaging.MessageTemplateKind;
import ro.myfinance.common.whatsapp.MetaTemplateText;
import ro.myfinance.common.whatsapp.WhatsAppTemplateValues;

/**
 * Fills a Meta template's positional variables server-side, from the module that owns the message. The
 * values are the same ones the module's email renders, cleaned into valid Meta parameters — the browser
 * only ever sees them, it never supplies them.
 */
@Service
public class WhatsAppTemplateVariables {

    private final Map<EmailKind, WhatsAppTemplateValues> sources = new EnumMap<>(EmailKind.class);

    public WhatsAppTemplateVariables(List<WhatsAppTemplateValues> sources) {
        for (WhatsAppTemplateValues source : sources) {
            if (this.sources.put(source.kind(), source) != null) {
                throw new IllegalStateException("Two WhatsApp template value sources for " + source.kind());
            }
        }
    }

    /** One filled variable: its 1-based Meta position, the placeholder name, and the cleaned value. */
    public record Variable(int position, String name, String value) {
    }

    /** The filled variables in position order, plus the names that resolved to nothing. */
    public record Resolved(List<Variable> variables, List<String> missing) {

        public static final Resolved NONE = new Resolved(List.of(), List.of());

        public boolean complete() {
            return missing.isEmpty();
        }

        /** Name → value, in position order (what the sender and the history row receive). */
        public Map<String, String> asMap() {
            Map<String, String> map = new LinkedHashMap<>();
            variables.forEach(v -> map.put(v.name(), v.value()));
            return map;
        }

        public List<String> values() {
            return variables.stream().map(Variable::value).toList();
        }
    }

    /** The ordered placeholder names a mapping declares ({@code variable_schema}, comma-separated). */
    public static List<String> names(String schema) {
        if (schema == null || schema.isBlank()) {
            return List.of();
        }
        return Arrays.stream(schema.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
    }

    /**
     * Resolves {@code names} for a company/period. A module that cannot word the message at all (e.g. a
     * tax declaration that does not reconcile) throws its own {@code ConflictException}.
     */
    public Resolved resolve(EmailKind kind, MessageTemplateKind sourceKind, UUID companyId, LocalDate period,
                            List<String> names) {
        if (names.isEmpty()) {
            return Resolved.NONE;
        }
        WhatsAppTemplateValues source = sources.get(kind);
        Map<String, String> raw = source == null ? Map.of()
                : source.values(sourceKind, companyId, period.withDayOfMonth(1));
        List<Variable> variables = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            String value = MetaTemplateText.parameter(raw.get(name));
            if (value.isEmpty()) {
                missing.add(name);
            }
            variables.add(new Variable(i + 1, name, value));
        }
        return new Resolved(List.copyOf(variables), List.copyOf(missing));
    }
}
