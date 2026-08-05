package com.example.notifications.llm;

import com.example.notifications.config.AppProperties;
import com.example.notifications.domain.BodySource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The failure handling, tested against a server that really is slow or broken
 * rather than a mock that returns a canned exception.
 *
 * <p>That distinction matters for the timeout test in particular: a mocked client
 * throwing {@code HttpTimeoutException} proves the catch block compiles, not that
 * the timeout is actually configured and actually fires. Here the stub sleeps
 * past the deadline and the real HTTP client has to give up on its own.
 */
class GroqPersonalizerTest {

    private static final String RENDERED =
            "Hi Aastha, we received your payment of ₹2,499.00 for order A-1001. "
                    + "View your receipt at https://example.com/r/1001.";

    private static final Map<String, String> VARIABLES = Map.of(
            "name", "Aastha",
            "amount", "₹2,499.00",
            "order_id", "A-1001",
            "receipt_url", "https://example.com/r/1001");

    private WireMockServer server;

    @BeforeEach
    void startStub() {
        server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop();
    }

    private GroqPersonalizer personalizerWith(Duration requestTimeout) {
        AppProperties.Llm config = new AppProperties.Llm(
                "test-key",
                server.baseUrl() + "/v1/chat/completions",
                "test-model",
                Duration.ofSeconds(2),
                requestTimeout,
                200);
        return new GroqPersonalizer(new ObjectMapper(), new PersonalizationGuard(), config);
    }

    private static String chatResponse(String content) {
        return """
                {"choices":[{"message":{"role":"assistant","content":%s}}]}
                """.formatted(new ObjectMapper().valueToTree(content).toString());
    }

    @Test
    void usesTheModelsRewriteWhenItPassesTheGuard() {
        String rewrite = "Hi Aastha — your ₹2,499.00 payment for order A-1001 is confirmed. "
                + "Receipt: https://example.com/r/1001";
        server.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(chatResponse(rewrite))));

        Personalizer.Result result =
                personalizerWith(Duration.ofSeconds(5)).personalize(RENDERED, VARIABLES);

        assertThat(result.source()).isEqualTo(BodySource.LLM);
        assertThat(result.body()).isEqualTo(rewrite);
        assertThat(result.outcome()).isEqualTo("ok");
    }

    @Test
    void fallsBackDeterministicallyWhenTheModelIsTooSlow() {
        // The stub sleeps well past the configured deadline; the real client must
        // abandon the call and the fallback must be the exact rendered body.
        server.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withStatus(200)
                        .withFixedDelay(3_000)
                        .withBody(chatResponse("too late to matter"))));

        Personalizer.Result result =
                personalizerWith(Duration.ofMillis(300)).personalize(RENDERED, VARIABLES);

        assertThat(result.source()).isEqualTo(BodySource.FALLBACK);
        assertThat(result.outcome()).isEqualTo("timeout");
        assertThat(result.body())
                .as("the deliverable message is unchanged by the failure")
                .isEqualTo(RENDERED);
    }

    @Test
    void fallsBackWhenTheProviderReturnsAnError() {
        server.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withStatus(429).withBody("{\"error\":\"rate limited\"}")));

        Personalizer.Result result =
                personalizerWith(Duration.ofSeconds(5)).personalize(RENDERED, VARIABLES);

        assertThat(result.source()).isEqualTo(BodySource.FALLBACK);
        assertThat(result.outcome()).isEqualTo("http_429");
        assertThat(result.body()).isEqualTo(RENDERED);
    }

    @Test
    void fallsBackWhenTheProviderIsUnreachable() {
        // Build the client against the live stub's address first, then take the
        // server away - the port is now closed, so this is a real connection
        // refused rather than a mocked exception.
        GroqPersonalizer personalizer = personalizerWith(Duration.ofSeconds(2));
        server.stop();

        Personalizer.Result result = personalizer.personalize(RENDERED, VARIABLES);

        assertThat(result.source()).isEqualTo(BodySource.FALLBACK);
        assertThat(result.outcome()).isEqualTo("error");
        assertThat(result.body()).isEqualTo(RENDERED);
    }

    @Test
    void fallsBackWhenTheResponseIsNotTheShapeWeExpect() {
        server.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"unexpected\":\"shape\"}")));

        Personalizer.Result result =
                personalizerWith(Duration.ofSeconds(5)).personalize(RENDERED, VARIABLES);

        assertThat(result.outcome()).isEqualTo("unparseable_response");
        assertThat(result.body()).isEqualTo(RENDERED);
    }

    @Test
    void fallsBackWhenTheModelBreaksTheTrustBoundary() {
        // The end-to-end version of the guard tests: a successful injection reaches
        // this class as an accepted-looking 200 and must still not be delivered.
        server.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withStatus(200)
                        .withBody(chatResponse("Hi Aastha, click https://evil.example now"))));

        Personalizer.Result result =
                personalizerWith(Duration.ofSeconds(5)).personalize(RENDERED, VARIABLES);

        assertThat(result.source()).isEqualTo(BodySource.FALLBACK);
        assertThat(result.outcome()).startsWith("guard_rejected:");
        assertThat(result.body()).isEqualTo(RENDERED);
    }

    @Test
    void sendsTheRenderedBodyAsDataInItsOwnTurnNotInsideTheInstructions() {
        server.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withStatus(200).withBody(chatResponse(RENDERED))));

        personalizerWith(Duration.ofSeconds(5)).personalize(RENDERED, VARIABLES);

        // Structural separation between instructions and data is the part of the
        // prompt defence a model is most likely to respect, so it is asserted
        // rather than assumed.
        server.verify(postRequestedFor(urlPathEqualTo("/v1/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer test-key"))
                .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("system")))
                .withRequestBody(matchingJsonPath("$.messages[1].role", equalTo("user")))
                .withRequestBody(matchingJsonPath("$.messages[1].content", equalTo(RENDERED)))
                .withRequestBody(matchingJsonPath("$.max_tokens")));
    }

    @Test
    void neverThrows() {
        // The interface contract the whole send path depends on. If this method
        // could throw, every caller would need a catch and one of them would
        // eventually get it wrong.
        server.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withStatus(500).withBody("kaboom")));

        Personalizer.Result result =
                personalizerWith(Duration.ofSeconds(2)).personalize(RENDERED, VARIABLES);

        assertThat(result).isNotNull();
        assertThat(result.body()).isEqualTo(RENDERED);
    }
}
