package com.urlcheck.ai.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.urlcheck.ai.config.AiConfig;
import com.urlcheck.ai.config.AiProperties;
import com.urlcheck.ai.memory.ConversationMemory;
import com.urlcheck.ai.message.ChatMessageService;
import com.urlcheck.check.ProbeResult;
import com.urlcheck.check.CheckService;
import com.urlcheck.check.CheckStatus;
import com.urlcheck.url.MonitoredUrl;
import com.urlcheck.url.MonitoredUrlService;

/**
 * The one thing the other tests cannot prove: that Spring AI discovers the
 * {@code @Tool} methods, describes them to a real provider, runs the call the
 * model asks for, and feeds the result back into an answer.
 *
 * <p>Off unless {@code AI_API_KEY} is set, because it reaches the network and
 * costs a request. Run it after touching the tools, the prompt, the memory or
 * the provider settings. It talks to the endpoint the environment names, so the
 * same command works whichever provider is configured:
 *
 * <pre>
 * AI_API_KEY=sk-... mvn test -Dtest=AiAgentIntegrationTest
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "AI_API_KEY", matches = ".+")
class AiAgentIntegrationTest {

    private static final long USER_ID = 1L;
    private static final long CONVERSATION_ID = 1L;
    private static final long QUESTION_ID = 1L;

    private final MonitoredUrlService urlService = mock(MonitoredUrlService.class);
    private final CheckService checkService = mock(CheckService.class);

    @Test
    void answersByCallingTheToolsRatherThanGuessing() {
        when(urlService.findAllForUser(USER_ID))
                .thenReturn(List.of(stored(1L, "Example", "https://example.com", "UP"),
                        stored(2L, "University", "https://university.edu", "DOWN")));
        when(checkService.probe(eq(USER_ID), anyLong())).thenReturn(new ProbeResult(
                2L, LocalDateTime.now(), CheckStatus.DOWN, null, 30L, null, null, null));

        new ApplicationContextRunner()
                .withUserConfiguration(AiConfig.class)
                .withPropertyValues(
                        "app.ai.enabled=true",
                        "app.ai.api-key=" + System.getenv("AI_API_KEY"),
                        "app.ai.base-url=" + envOr("AI_BASE_URL", "https://api.deepseek.com"),
                        "app.ai.model=" + envOr("AI_MODEL", "deepseek-flash"),
                        "app.ai.temperature=0.2",
                        "app.ai.max-tokens=1024",
                        "app.ai.max-retries=2",
                        "app.ai.timeout-seconds=60",
                        "app.ai.max-urls-per-check=10",
                        "app.ai.memory-max-messages=20",
                        "app.ai.max-conversations=5")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AiProperties properties = context.getBean(AiProperties.class);
                    // The conversation itself is beside the point here; what is
                    // under test is that the tools reach a real provider.
                    ConversationMemory memory = new ConversationMemory(
                            context.getBean(ChatMemory.class), mock(ChatMessageService.class), properties);
                    AiAgent agent = new AiAgent(
                            context.getBean(ChatClient.class), urlService, checkService, properties, memory,
                            Optional.empty());

                    AiAgent.AgentReply reply =
                            agent.chat(USER_ID, CONVERSATION_ID, QUESTION_ID, "Which of my URLs is unreachable?");

                    // The name can only come from a tool: nothing in the prompt mentions it.
                    assertThat(reply.message()).containsIgnoringCase("University");
                    // at least once, not exactly once: the model is free to call
                    // listUrls, checkUrls, or both.
                    verify(urlService, atLeastOnce()).findAllForUser(USER_ID);
                    assertThat(reply.proposedDeletions()).isEmpty();
                });
    }

    /** Follows the provider the deployment is configured for, not a hard-coded one. */
    private static String envOr(String variable, String fallback) {
        String value = System.getenv(variable);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static MonitoredUrl stored(long id, String name, String address, String lastStatus) {
        MonitoredUrl url = new MonitoredUrl();
        url.setId(id);
        url.setName(name);
        url.setUrl(address);
        url.setDescription("note");
        url.setLastStatus(lastStatus);
        url.setLastHttpStatus("UP".equals(lastStatus) ? 200 : 404);
        url.setLastCheckedAt(LocalDateTime.now());
        url.setChangeCount(1L);
        return url;
    }
}
