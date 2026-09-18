package com.urlcheck.ai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.urlcheck.ai.agent.AiAgent;
import com.urlcheck.ai.controller.AiController;
import com.urlcheck.ai.controller.ConversationController;
import com.urlcheck.ai.conversation.ConversationMapper;
import com.urlcheck.ai.conversation.ConversationService;
import com.urlcheck.ai.deletion.PendingDeletions;
import com.urlcheck.ai.memory.ConversationMemory;
import com.urlcheck.ai.message.ChatMessageMapper;
import com.urlcheck.ai.message.ChatMessageService;
import com.urlcheck.check.CheckService;
import com.urlcheck.url.MonitoredUrlService;

/**
 * The chat client is wired by hand against the OpenAI SDK, so nothing else
 * exercises it. These cases pin the behaviour a deployment depends on: a key
 * alone is enough to get the assistant, an explicitly enabled deployment with
 * no key fails at startup rather than on the first user question, and
 * everything the endpoints need is assembled when it is on.
 *
 * <p>The services behind the endpoints answer to the same switch, which is its
 * own case below: registered unconditionally they cannot be created with the
 * assistant off, and the missing {@code AiProperties} takes the whole
 * application down at startup.
 */
class AiConfigTest {

    private static final String[] PROVIDER = {
            "app.ai.base-url=https://api.deepseek.com",
            "app.ai.model=deepseek-flash",
            "app.ai.temperature=0.2",
            "app.ai.max-tokens=1024",
            "app.ai.max-retries=2",
            "app.ai.timeout-seconds=60",
            "app.ai.max-urls-per-check=10",
            "app.ai.memory-max-messages=20",
            "app.ai.max-conversations=5"};

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiConfig.class)
            .withPropertyValues(PROVIDER);

    @Test
    void buildsAChatClientFromTheConfiguredProvider() {
        runner.withPropertyValues("app.ai.enabled=true", "app.ai.api-key=sk-test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ChatClient.class);
                    assertThat(context).hasSingleBean(ChatMemory.class);
                });
    }

    @Test
    void failsFastWhenEnabledWithoutAKey() {
        runner.withPropertyValues("app.ai.enabled=true", "app.ai.api-key=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable root = context.getStartupFailure();
                    while (root.getCause() != null) {
                        root = root.getCause();
                    }
                    assertThat(root)
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("app.ai.api-key");
                });
    }

    @Test
    void staysAbsentWhenTheAssistantIsOff() {
        runner.withPropertyValues("app.ai.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ChatClient.class);
                    assertThat(context).doesNotHaveBean(ConversationController.class);
                });
    }

    /**
     * The rule a deployment leans on: no flag, but a key, and the assistant is
     * there. AI_ENABLED is an override, not a prerequisite.
     */
    @Test
    void followsTheCredentialWhenTheSwitchIsUnset() {
        runner.withPropertyValues("app.ai.api-key=sk-test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ChatClient.class);
                });
    }

    /** With neither the key nor the flag, nothing is added to the context. */
    @Test
    void staysAbsentWhenNeitherAKeyNorTheSwitchIsSet() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ChatClient.class);
        });
    }

    /** The other override: an explicit false wins over a configured key. */
    @Test
    void turnsOffEvenWithAKeyWhenExplicitlyDisabled() {
        runner.withPropertyValues("app.ai.enabled=false", "app.ai.api-key=sk-test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ChatClient.class);
                });
    }

    /**
     * The endpoints only exist if every part of them can be created, so this
     * assembles the agent, the memory and both controllers next to the chat
     * client. The URL, conversation and message services are stubbed: the point
     * is the wiring, not the business logic.
     */
    @Test
    void assemblesTheEndpointsWhenEnabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(AiConfig.class, AiAgent.class, ConversationMemory.class,
                        AiController.class, ConversationController.class, PendingDeletions.class)
                .withBean(MonitoredUrlService.class, () -> mock(MonitoredUrlService.class))
                .withBean(CheckService.class, () -> mock(CheckService.class))
                .withBean(ConversationService.class, () -> mock(ConversationService.class))
                .withBean(ChatMessageService.class, () -> mock(ChatMessageService.class))
                .withPropertyValues(PROVIDER)
                .withPropertyValues("app.ai.enabled=true", "app.ai.api-key=sk-test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AiController.class);
                    assertThat(context).hasSingleBean(AiAgent.class);
                    assertThat(context).hasSingleBean(ConversationController.class);
                    assertThat(context).hasSingleBean(ConversationMemory.class);
                });
    }

    /**
     * The conversation services are part of the assistant, so they carry its
     * switch. This is the case that caught them registered unconditionally: the
     * context must come up with the assistant off, and hold none of them.
     */
    @Test
    void keepsTheConversationServicesOutOfTheContextWhenTheAssistantIsOff() {
        conversationSlice()
                .withPropertyValues("app.ai.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ConversationService.class);
                    assertThat(context).doesNotHaveBean(ConversationMemory.class);
                    assertThat(context).doesNotHaveBean(ChatMemory.class);
                });
    }

    /** The other half of the switch: on, and the same services are there. */
    @Test
    void wiresTheConversationServicesWhenTheAssistantIsOn() {
        conversationSlice()
                .withPropertyValues("app.ai.enabled=true", "app.ai.api-key=sk-test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ConversationService.class);
                    assertThat(context).hasSingleBean(ConversationMemory.class);
                });
    }

    /**
     * The conversation side of the assistant on its own, with the mappers
     * stubbed: the question is which beans the switch registers, not what they
     * do with a mapper.
     */
    private static ApplicationContextRunner conversationSlice() {
        return new ApplicationContextRunner()
                .withUserConfiguration(AiConfig.class, ConversationService.class,
                        ConversationMemory.class, ChatMessageService.class)
                .withBean(ConversationMapper.class, () -> mock(ConversationMapper.class))
                .withBean(ChatMessageMapper.class, () -> mock(ChatMessageMapper.class))
                .withPropertyValues(PROVIDER);
    }
}
