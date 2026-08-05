package com.example.notifications.llm;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a template to the deterministic body that is the ground truth for
 * everything downstream.
 *
 * <p>This runs <em>before</em> the model and its output is what the model is asked
 * to rewrite, what {@link PersonalizationGuard} checks the rewrite against, and
 * what gets delivered if the model is slow, fails, or produces something the guard
 * rejects. Every path through the service therefore ends in a correct message; the
 * model can only change how it reads.
 *
 * <p>Templates are a fixed, server-side set - callers name one, they do not supply
 * one. A caller-supplied template would be a server-side template injection
 * surface and would let a caller decide which facts exist, which is precisely the
 * authority this design is trying to keep away from user input.
 */
@Component
public class TemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*(\\w+)\\s*}}");

    /**
     * The template catalogue. Kept in code rather than the database because a
     * template is a deployable artifact, reviewed like code - and because a
     * database-backed template that a caller could ever write to would reintroduce
     * the injection surface this class exists to close.
     */
    private static final Map<String, Template> TEMPLATES = Map.of(
            "payment_received", new Template(
                    "payment_received",
                    "Hi {{name}}, we received your payment of {{amount}} for order {{order_id}}. "
                            + "View your receipt at {{receipt_url}}."),
            "order_shipped", new Template(
                    "order_shipped",
                    "Hi {{name}}, order {{order_id}} has shipped and should arrive by {{eta}}. "
                            + "Track it at {{tracking_url}}."),
            "payment_failed", new Template(
                    "payment_failed",
                    "Hi {{name}}, we could not process your payment of {{amount}} for order "
                            + "{{order_id}}. Update your payment method at {{update_url}}."),
            "welcome", new Template(
                    "welcome",
                    "Hi {{name}}, welcome to {{product}}. Get started at {{start_url}}."));

    public boolean isKnownTemplate(String name) {
        return TEMPLATES.containsKey(name);
    }

    public java.util.Set<String> templateNames() {
        return TEMPLATES.keySet();
    }

    /**
     * Substitutes {@code variables} into the named template.
     *
     * <p>Substitution is a plain string replacement of {@code {{name}}} markers and
     * nothing else - no expression evaluation, no nested lookup, no recursion into
     * substituted values. A value containing {@code {{other}}} is inert text, so a
     * caller cannot use one variable to reach a second substitution pass.
     *
     * @throws IllegalArgumentException if the template is unknown or a placeholder
     *                                  has no value; failing loudly beats
     *                                  delivering a message with a literal
     *                                  {@code {{amount}}} in it
     */
    public Rendered render(String templateName, Map<String, String> variables) {
        Template template = TEMPLATES.get(templateName);
        if (template == null) {
            throw new IllegalArgumentException("unknown template: " + templateName);
        }

        Map<String, String> used = new LinkedHashMap<>();
        StringBuilder out = new StringBuilder();
        Matcher m = PLACEHOLDER.matcher(template.body());
        while (m.find()) {
            String key = m.group(1);
            String value = variables.get(key);
            if (value == null) {
                throw new IllegalArgumentException(
                        "missing variable '" + key + "' for template '" + templateName + "'");
            }
            used.put(key, value);
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);

        return new Rendered(out.toString(), used);
    }

    private record Template(String name, String body) {
    }

    /**
     * @param body         the deterministic message
     * @param usedVariables only the variables this template actually substituted -
     *                      the guard enforces these and ignores extras, so an
     *                      unused variable cannot be used to force a fallback
     */
    public record Rendered(String body, Map<String, String> usedVariables) {
    }
}
