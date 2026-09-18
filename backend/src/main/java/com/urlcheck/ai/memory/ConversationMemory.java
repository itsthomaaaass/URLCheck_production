package com.urlcheck.ai.memory;

import java.util.List;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.urlcheck.ai.config.AiEnabledCondition;
import com.urlcheck.ai.config.AiProperties;
import com.urlcheck.ai.conversation.ConversationsEvictedEvent;
import com.urlcheck.ai.message.ChatMessage;
import com.urlcheck.ai.message.ChatMessageService;

/**
 * Keeps Spring AI's {@link ChatMemory} in step with the conversation history
 * stored in MySQL.
 *
 * <p>The two are deliberately different things. MySQL holds the complete record,
 * which is what the user reads. ChatMemory holds only the window of recent
 * messages the model is shown, and it is rebuilt from the stored history before
 * every turn rather than accumulated in memory. That is what makes a backend
 * restart, a second instance, or a conversation dropped by retention harmless:
 * the model's context is derived from MySQL, so it can never be staler or fuller
 * than the record it came from.
 *
 * <p>Cost is bounded by {@code app.ai.memory-max-messages}, the same number the
 * window itself keeps, so a long conversation still costs a fixed prompt.
 *
 * <p>Absent when {@code app.ai.enabled} is false, because the {@code ChatMemory}
 * it reconciles with is absent too.
 */
@Component
@Conditional(AiEnabledCondition.class)
public class ConversationMemory {

    private final ChatMemory chatMemory;
    private final ChatMessageService messages;
    private final int maxMessages;

    public ConversationMemory(ChatMemory chatMemory, ChatMessageService messages, AiProperties properties) {
        this.chatMemory = chatMemory;
        this.messages = messages;
        this.maxMessages = Math.max(1, properties.memoryMaxMessages());
    }

    /**
     * Replaces the model's context for one conversation with the stored messages
     * that came before the question.
     *
     * <p>The question itself is deliberately not part of the window: it is sent
     * as the prompt of the call, and replaying it here would send it twice.
     *
     * @param conversationId    the thread the question belongs to
     * @param questionMessageId the stored row of the question being asked
     */
    public void seed(Long conversationId, Long questionMessageId) {
        String key = key(conversationId);
        chatMemory.clear(key);

        List<ChatMessage> history =
                messages.findWindowBefore(conversationId, questionMessageId, maxMessages);
        if (history.isEmpty()) {
            return;
        }
        chatMemory.add(key, history.stream().map(ConversationMemory::toSpringAiMessage).toList());
    }

    /**
     * Drops the context of conversations that no longer exist.
     *
     * <p>Listened for rather than called by the services, so the conversation
     * module stays unaware of the model and the request path stays unaware of
     * retention. Nothing is lost if this clears a conversation that a rolled
     * back transaction did not actually delete: the next turn rebuilds the
     * window from MySQL either way.
     */
    @EventListener
    public void onConversationsEvicted(ConversationsEvictedEvent event) {
        event.conversationIds().forEach(conversationId -> chatMemory.clear(key(conversationId)));
    }

    /** The key Spring AI looks a conversation's memory up by. */
    public static String key(Long conversationId) {
        return String.valueOf(conversationId);
    }

    private static Message toSpringAiMessage(ChatMessage message) {
        return switch (message.getRole()) {
            case USER -> new UserMessage(message.getContent());
            case ASSISTANT -> new AssistantMessage(message.getContent());
        };
    }
}
