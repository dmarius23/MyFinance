package ro.myfinance.settings.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import ro.myfinance.common.email.EmailKind;
import ro.myfinance.common.messaging.MessageTemplateKind;
import ro.myfinance.common.messaging.MessageTemplateService;
import ro.myfinance.common.messaging.TemplateRenderer;

/** Converts the editable tenant message wording into a reviewable Meta body draft. */
@org.springframework.stereotype.Service
public class MetaWhatsAppTemplateDraftService {

    private final MessageTemplateService templates;

    public MetaWhatsAppTemplateDraftService(MessageTemplateService templates) {
        this.templates = templates;
    }

    public Draft create(EmailKind kind, MessageTemplateKind reminderKind) {
        MessageTemplateKind source = sourceKind(kind, reminderKind);
        String body = templates.effective(source).body();
        Matcher matcher = TemplateRenderer.TOKEN.matcher(body);
        Map<String, Integer> positions = new LinkedHashMap<>();
        StringBuffer converted = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            int position = positions.computeIfAbsent(name, ignored -> positions.size() + 1);
            matcher.appendReplacement(converted, Matcher.quoteReplacement("{{" + position + "}}"));
        }
        matcher.appendTail(converted);
        return new Draft(source, converted.toString(), new ArrayList<>(positions.keySet()));
    }

    private static MessageTemplateKind sourceKind(EmailKind kind, MessageTemplateKind reminderKind) {
        if (kind == EmailKind.TAX) {
            return MessageTemplateKind.TAX;
        }
        if (kind == EmailKind.PAYROLL) {
            return MessageTemplateKind.PAYROLL;
        }
        if (kind == EmailKind.REPORT) {
            return MessageTemplateKind.REPORT;
        }
        if (kind == EmailKind.DOCUMENT_REMINDER && reminderKind != null && reminderKind.isReminder()) {
            return reminderKind;
        }
        throw new IllegalArgumentException(
                "A reminder state is required when generating a document-reminder WhatsApp template");
    }

    public record Draft(MessageTemplateKind sourceKind, String body, List<String> variableNames) {
    }
}
