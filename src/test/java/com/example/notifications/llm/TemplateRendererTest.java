package com.example.notifications.llm;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateRendererTest {

    private final TemplateRenderer renderer = new TemplateRenderer();

    @Test
    void substitutesEveryPlaceholder() {
        TemplateRenderer.Rendered rendered = renderer.render("payment_received", Map.of(
                "name", "Aastha", "amount", "₹2,499.00",
                "order_id", "A-1001", "receipt_url", "https://example.com/r/1001"));

        assertThat(rendered.body())
                .contains("Aastha").contains("₹2,499.00")
                .contains("A-1001").contains("https://example.com/r/1001")
                .doesNotContain("{{");
    }

    @Test
    void reportsOnlyTheVariablesTheTemplateActuallyUsed() {
        // The guard enforces exactly this set, so an unused variable must not end
        // up in it - otherwise a caller could force a permanent fallback by
        // passing something the model has no way to include.
        TemplateRenderer.Rendered rendered = renderer.render("welcome", Map.of(
                "name", "Aastha", "product", "Acme",
                "start_url", "https://example.com/start",
                "irrelevant", "not-in-this-template"));

        assertThat(rendered.usedVariables()).containsOnlyKeys("name", "product", "start_url");
    }

    @Test
    void refusesAnUnknownTemplate() {
        assertThatThrownBy(() -> renderer.render("does_not_exist", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown template");
    }

    @Test
    void refusesAMissingVariableRatherThanShippingALiteralPlaceholder() {
        assertThatThrownBy(() -> renderer.render("payment_received", Map.of("name", "Aastha")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing variable");
    }

    @Test
    void doesNotRecurseIntoSubstitutedValues() {
        // A value containing {{...}} must stay inert text. If substitution ran a
        // second pass, a caller could use one variable to reach another.
        TemplateRenderer.Rendered rendered = renderer.render("welcome", Map.of(
                "name", "{{start_url}}", "product", "Acme",
                "start_url", "https://example.com/start"));

        assertThat(rendered.body()).contains("{{start_url}}");
    }

    @Test
    void treatsDollarAndBackslashInValuesAsLiteralText() {
        // Regex replacement metacharacters would otherwise corrupt the output or
        // throw; amounts routinely contain '$'.
        TemplateRenderer.Rendered rendered = renderer.render("payment_received", Map.of(
                "name", "A$tha\\x", "amount", "$1,000.00",
                "order_id", "A-1001", "receipt_url", "https://example.com/r/1001"));

        assertThat(rendered.body()).contains("A$tha\\x").contains("$1,000.00");
    }
}
