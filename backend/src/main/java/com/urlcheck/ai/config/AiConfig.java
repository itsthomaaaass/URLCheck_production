package com.urlcheck.ai.config;

import java.time.Duration;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.util.StringUtils;

/**
 * Wires a chat client to whichever OpenAI-compatible endpoint the environment
 * names.
 *
 * <p>Built by hand instead of relying on Spring AI's auto-configuration, so the
 * assistant is present only when it is switched on and configured: with no AI
 * key it is absent and the application behaves exactly as it did before, and
 * the provider, model and limits come from the environment rather than from a
 * compiled-in default.
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
@Conditional(AiEnabledCondition.class)
@Import(ChatMemoryConfig.class)
public class AiConfig {

    /**
     * The provider connection itself. The SDK client is a bean rather than a
     * local, so its connection pool is closed when the application shuts down.
     *
     * <p>The HTTP client has to be handed over explicitly: the SDK does not pick
     * one for you, and Spring AI's OkHttp one is the same implementation its own
     * auto-configuration would use.
     *
     * <p>{@code OpenAiChatModel} wants the blocking and the streaming client, so
     * both are handed to it below; the async one shares this connection.
     *
     * @throws IllegalStateException when the assistant is enabled without a key,
     *         so the deployment fails at startup instead of on the first
     *         question the user asks
     */
    @Bean(destroyMethod = "close")
    OpenAIClient aiOpenAiClient(AiProperties properties) {
        if (!StringUtils.hasText(properties.apiKey())) {
            throw new IllegalStateException(
                    "app.ai.enabled=true but app.ai.api-key is empty:"
                            + " set AI_API_KEY, or turn the assistant off with AI_ENABLED=false");
        }

        SpringAiOpenAiHttpClient httpClient = SpringAiOpenAiHttpClient.builder()
                .timeout(Duration.ofSeconds(properties.timeoutSeconds()))
                .build();

        ClientOptions clientOptions = ClientOptions.builder()
                .httpClient(httpClient)
                .apiKey(properties.apiKey())
                .baseUrl(properties.baseUrl())
                .maxRetries(properties.maxRetries())
                .timeout(Duration.ofSeconds(properties.timeoutSeconds()))
                .build();

        return new OpenAIClientImpl(clientOptions);
    }

    /**
     * The chat client every assistant call goes through.
     *
     * <p>The memory advisor is registered as a default, so each prompt carries
     * the conversation's recent history without the caller having to add it. In
     * exchange the advisor requires a conversation id on every call
     * ({@link ChatMemory#CONVERSATION_ID}), which
     * {@link com.urlcheck.ai.agent.AiAgent} supplies.
     */
    @Bean
    ChatClient aiChatClient(OpenAIClient openAiClient, AiProperties properties, ChatMemory chatMemory) {
        OpenAiChatOptions chatOptions = OpenAiChatOptions.builder()
                .model(properties.model())
                .temperature(properties.temperature())
                .maxTokens(properties.maxTokens())
                .build();

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiClient(openAiClient)
                .openAiClientAsync(openAiClient.async())
                .options(chatOptions)
                .build();

        return ChatClient.builder(chatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }
}
