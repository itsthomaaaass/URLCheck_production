package com.urlcheck.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection details and limits for the AI assistant, bound from
 * {@code app.ai.*} and therefore from the environment. The provider, the model
 * and the endpoint are all variables, so the same build can run against
 * OpenRouter, OpenAI or a self-hosted OpenAI-compatible server, and the model
 * can be swapped without a rebuild.
 *
 * @param enabled          explicit switch; {@code null} when unset, which
 *                         leaves the decision to {@link AiEnabledCondition}
 *                         and so to whether a provider key is configured
 * @param baseUrl          OpenAI-compatible API root, including the version path
 * @param apiKey           provider credential; stays on the backend
 * @param model            provider-specific model name, e.g. a tool-capable model
 * @param temperature      sampling temperature, kept low because the answers are factual
 * @param maxTokens        answer length cap, bounding cost per message
 * @param maxRetries       retries on a rate-limited or failing provider call
 * @param timeoutSeconds   how long one provider call may take
 * @param maxUrlsPerCheck  URLs probed per {@code checkUrls} call, bounding latency
 * @param memoryMaxMessages messages of a conversation replayed to the model as context
 * @param maxConversations conversations kept per user; least recently used are deleted first
 */
@ConfigurationProperties(prefix = "app.ai")
public record AiProperties(
        Boolean enabled,
        String baseUrl,
        String apiKey,
        String model,
        double temperature,
        int maxTokens,
        int maxRetries,
        long timeoutSeconds,
        int maxUrlsPerCheck,
        int memoryMaxMessages,
        int maxConversations) {
}
