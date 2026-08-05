package com.example.notifications.llm;

import java.util.Map;

/**
 * The no-model personaliser: returns the deterministic render, always.
 *
 * <p>Wired in when no API key is configured, so a fresh clone with an empty
 * environment starts, serves, and passes its tests with no credentials and no
 * network. That is not only a developer convenience - it means the fallback path
 * is the default path in local development and CI, so the branch that has to work
 * when the model is down is the branch that gets exercised on every build.
 */
public class DeterministicPersonalizer implements Personalizer {

    @Override
    public Result personalize(String renderedBody, Map<String, String> usedVariables) {
        return Result.fallback(renderedBody, "disabled", 0L);
    }
}
