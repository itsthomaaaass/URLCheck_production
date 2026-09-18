package com.urlcheck.ai.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * The assistant's conversational memory.
 *
 * <p>Exposed as a bean so the whole request path shares one store:
 * {@link com.urlcheck.ai.memory.ConversationMemory} writes the window it replays
 * to the model, and dropping a conversation clears it.
 *
 * <p>The window is bounded because the model is only ever shown the most recent
 * messages. Older ones stay in MySQL and are simply not replayed, so a
 * conversation that runs long costs a fixed prompt instead of a growing one.
 */
@Configuration
@Conditional(AiEnabledCondition.class)
public class ChatMemoryConfig {

    @Bean
    ChatMemory aiChatMemory(AiProperties properties) {
        return MessageWindowChatMemory.builder()
                .maxMessages(Math.max(1, properties.memoryMaxMessages()))
                .build();
    }
}
