package com.notificationengine.service;

import com.notificationengine.model.entity.NotificationTemplate;
import com.notificationengine.repository.NotificationTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves notification templates and replaces {{placeholder}} tokens
 * with provided parameter values.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TemplateEngine {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{(\\w+)}}");

    private final NotificationTemplateRepository templateRepository;

    /**
     * Resolve a template by code and render it with provided parameters.
     */
    public RenderedTemplate render(String templateCode, Map<String, String> params) {
        NotificationTemplate template = templateRepository
                .findByTemplateCodeAndIsActiveTrue(templateCode)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Template not found or inactive: " + templateCode));

        String renderedBody = replacePlaceholders(template.getBodyTemplate(), params);
        String renderedSubject = template.getSubject() != null
                ? replacePlaceholders(template.getSubject(), params)
                : null;

        log.debug("Rendered template '{}' with {} params", templateCode, params.size());
        return new RenderedTemplate(renderedSubject, renderedBody, template.getChannel());
    }

    /**
     * Replace all {{key}} placeholders with values from the params map.
     * Unresolved placeholders are left as-is with a warning.
     */
    private String replacePlaceholders(String text, Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return text;
        }

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();

        while (matcher.find()) {
            String key = matcher.group(1);
            String value = params.getOrDefault(key, "");
            if (value.isEmpty()) {
                log.warn("Unresolved placeholder: {{{{{}}}}}", key);
                value = "{{" + key + "}}";
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    public record RenderedTemplate(
            String subject,
            String body,
            com.notificationengine.model.enums.NotificationChannel channel
    ) {}
}
