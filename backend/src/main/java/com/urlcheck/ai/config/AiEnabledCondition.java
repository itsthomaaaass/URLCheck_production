package com.urlcheck.ai.config;

import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * The assistant's one switch: the chat client, the agent, the memory, the
 * conversation services and both controllers all answer to it, so they cannot
 * drift apart and leave half an assistant in the context.
 *
 * <p>{@code AI_ENABLED} wins whenever it is set. {@code false} is therefore a
 * kill switch that works even with a key configured, and {@code true} still
 * demands a key: {@link AiConfig} then fails at startup, where a typo costs
 * nothing, instead of on the first question a user asks.
 *
 * <p>Left unset, the switch follows the credential: a deployment that supplies
 * {@code AI_API_KEY} gets the assistant, and one that supplies no key is
 * exactly the application it was before the assistant existed. There is no
 * flag to remember, and no way to switch it on with nothing to call.
 */
public class AiEnabledCondition extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment environment = context.getEnvironment();

        String configured = environment.getProperty("app.ai.enabled");
        if (StringUtils.hasText(configured)) {
            String reason = String.format("app.ai.enabled is %s", configured.trim());
            return new ConditionOutcome(Boolean.parseBoolean(configured.trim()),
                    ConditionMessage.forCondition("AiEnabledCondition").because(reason));
        }

        boolean keyPresent = StringUtils.hasText(environment.getProperty("app.ai.api-key"));
        return new ConditionOutcome(keyPresent,
                ConditionMessage.forCondition("AiEnabledCondition")
                        .because(keyPresent
                                ? "a provider key is configured"
                                : "no provider key and no explicit app.ai.enabled"));
    }
}
