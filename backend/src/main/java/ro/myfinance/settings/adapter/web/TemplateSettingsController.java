package ro.myfinance.settings.adapter.web;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.common.messaging.DefaultMessageTemplates;
import ro.myfinance.common.messaging.MessageTemplateKind;
import ro.myfinance.common.messaging.MessageTemplateService;
import ro.myfinance.common.messaging.SampleTemplateValues;
import ro.myfinance.common.messaging.TemplateRenderer;
import ro.myfinance.settings.adapter.web.TemplateSettingsDtos.PreviewRequest;
import ro.myfinance.settings.adapter.web.TemplateSettingsDtos.PreviewResponse;
import ro.myfinance.settings.adapter.web.TemplateSettingsDtos.TemplateResponse;
import ro.myfinance.settings.adapter.web.TemplateSettingsDtos.UpdateTemplateRequest;
import ro.myfinance.settings.application.TemplateValidator;

/**
 * Per-tenant message wording, managed by the firm admin. TENANT_ADMIN only, like the provider
 * settings next to it: wording goes to every client of the firm, so it is not a per-employee
 * preference.
 */
@RestController
@RequestMapping("/api/v1/settings/messaging/templates")
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class TemplateSettingsController {

    private final MessageTemplateService service;
    private final TemplateValidator validator;
    private final TemplateRenderer renderer;

    public TemplateSettingsController(MessageTemplateService service, TemplateValidator validator,
                                      TemplateRenderer renderer) {
        this.service = service;
        this.validator = validator;
        this.renderer = renderer;
    }

    /** Every kind, showing the tenant's text where customized and the built-in default otherwise. */
    @GetMapping
    public List<TemplateResponse> list() {
        return service.effectiveAll().stream().map(TemplateResponse::from).toList();
    }

    @PutMapping("/{kind}")
    public TemplateResponse update(@PathVariable MessageTemplateKind kind,
                                   @Valid @RequestBody UpdateTemplateRequest req) {
        validator.validate(kind, req.subject(), req.body());
        service.save(kind, req.subject(), req.body());
        return TemplateResponse.from(service.effective(kind));
    }

    /** Drop the override so this kind renders the built-in default again. */
    @DeleteMapping("/{kind}")
    public TemplateResponse reset(@PathVariable MessageTemplateKind kind) {
        service.reset(kind);
        return TemplateResponse.from(service.effective(kind));
    }

    /**
     * Render the submitted (unsaved) text against sample data, so previewing needs no real company —
     * and no real client data ever appears in the Settings screen.
     */
    @PostMapping("/{kind}/preview")
    public PreviewResponse preview(@PathVariable MessageTemplateKind kind,
                                   @Valid @RequestBody PreviewRequest req) {
        validator.validate(kind, req.subject(), req.body());
        var values = SampleTemplateValues.forKind(kind);
        String subject = (req.subject() == null || req.subject().isBlank())
                ? DefaultMessageTemplates.of(kind).subject()
                : req.subject();
        return new PreviewResponse(
                renderer.render(subject, values),
                renderer.render(req.body(), values),
                values);
    }
}
