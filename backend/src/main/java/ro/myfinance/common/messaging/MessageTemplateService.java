package ro.myfinance.common.messaging;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.security.TenantContext;

/**
 * Resolves and renders the wording for a message kind: the current tenant's row when it has one, the
 * built-in {@link DefaultMessageTemplates} otherwise. A tenant may reword the body alone — a null
 * subject on the row keeps the built-in subject.
 *
 * <p>All reads and writes are scoped to the bound tenant and additionally enforced by RLS. With no
 * tenant bound this fails closed rather than reading another firm's wording.
 */
@Service
@Transactional
public class MessageTemplateService {

    private final MessageTemplateRepository repo;
    private final TemplateRenderer renderer;

    public MessageTemplateService(MessageTemplateRepository repo, TemplateRenderer renderer) {
        this.repo = repo;
        this.renderer = renderer;
    }

    /** A kind's current wording plus whether this tenant has overridden it. */
    public record Effective(MessageTemplateKind kind, String subject, String body, boolean customized) {
    }

    @Transactional(readOnly = true)
    public RenderedMessage render(MessageTemplateKind kind, Map<String, String> values) {
        Effective e = effective(kind);
        return new RenderedMessage(renderer.render(e.subject(), values), renderer.render(e.body(), values));
    }

    @Transactional(readOnly = true)
    public Effective effective(MessageTemplateKind kind) {
        DefaultMessageTemplates.Template fallback = DefaultMessageTemplates.of(kind);
        Optional<MessageTemplate> row = repo.findByTenantIdAndKind(tenant(), kind);
        return row.map(r -> buildEffective(kind, r, fallback))
                .orElseGet(() -> new Effective(kind, fallback.subject(), fallback.body(), false));
    }

    /** Every kind, for the settings screen — customized rows merged over the defaults. */
    @Transactional(readOnly = true)
    public List<Effective> effectiveAll() {
        Map<MessageTemplateKind, MessageTemplate> rows = new EnumMap<>(MessageTemplateKind.class);
        for (MessageTemplate r : repo.findByTenantId(tenant())) {
            rows.put(r.getKind(), r);
        }
        List<Effective> out = new ArrayList<>();
        for (MessageTemplateKind kind : MessageTemplateKind.values()) {
            DefaultMessageTemplates.Template fallback = DefaultMessageTemplates.of(kind);
            MessageTemplate r = rows.get(kind);
            out.add(r == null
                    ? new Effective(kind, fallback.subject(), fallback.body(), false)
                    : buildEffective(kind, r, fallback));
        }
        return out;
    }

    /** Upsert this tenant's wording. Validation happens in the web layer before this is called. */
    public void save(MessageTemplateKind kind, String subject, String body) {
        UUID tenantId = tenant();
        MessageTemplate row = repo.findByTenantIdAndKind(tenantId, kind)
                .orElseGet(() -> new MessageTemplate(tenantId, kind));
        row.setSubject(trimToNull(TemplateRenderer.normalize(subject)));
        row.setBody(TemplateRenderer.normalize(body));
        row.setUpdatedBy(TenantContext.current().map(TenantContext.Identity::userId).orElse(null));
        repo.save(row);
    }

    /** Drop the override, so this kind renders the built-in default again. */
    public void reset(MessageTemplateKind kind) {
        repo.deleteByTenantIdAndKind(tenant(), kind);
    }

    /**
     * Builds an {@link Effective} for a tenant row that exists, applying the null/blank-subject fallback.
     * A tenant may reword the body only — a null or blank subject keeps the built-in subject.
     */
    private static Effective buildEffective(MessageTemplateKind kind, MessageTemplate row,
            DefaultMessageTemplates.Template fallback) {
        String subject = (row.getSubject() == null || row.getSubject().isBlank())
                ? fallback.subject()
                : row.getSubject();
        return new Effective(kind, subject, row.getBody(), true);
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static UUID tenant() {
        return TenantContext.tenantId()
                .orElseThrow(() -> new IllegalStateException("No tenant bound — refusing to resolve templates"));
    }
}
