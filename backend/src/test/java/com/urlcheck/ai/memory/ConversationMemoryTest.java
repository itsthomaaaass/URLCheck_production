package com.urlcheck.ai.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;

import com.urlcheck.ai.config.AiProperties;
import com.urlcheck.ai.conversation.ConversationsEvictedEvent;
import com.urlcheck.ai.message.ChatMessage;
import com.urlcheck.ai.message.ChatMessageService;
import com.urlcheck.ai.message.ChatRole;

/**
 * The window the model is shown is derived from MySQL before every turn, so
 * these cases pin the two things that makes true: the window is rebuilt rather
 * than accumulated, and it stops at the newest message that came before the
 * question.
 */
class ConversationMemoryTest {

    private static final long CONVERSATION_ID = 101L;
    private static final long QUESTION_ID = 1000L;
    private static final int WINDOW = 20;

    private final ChatMessageService messages = mock(ChatMessageService.class);
    private final ChatMemory chatMemory = MessageWindowChatMemory.builder().maxMessages(WINDOW).build();
    private final ConversationMemory memory =
            new ConversationMemory(chatMemory, messages, properties(WINDOW));

    @Test
    void replaysTheStoredHistoryToTheModel() {
        when(messages.findWindowBefore(CONVERSATION_ID, QUESTION_ID, WINDOW)).thenReturn(List.of(
                message(ChatRole.USER, "What is URL monitoring?"),
                message(ChatRole.ASSISTANT, "It checks a URL on a schedule.")));

        memory.seed(CONVERSATION_ID, QUESTION_ID);

        assertThat(chatMemory.get(ConversationMemory.key(CONVERSATION_ID)))
                .extracting(Message::getMessageType)
                .containsExactly(MessageType.USER, MessageType.ASSISTANT);
        assertThat(chatMemory.get(ConversationMemory.key(CONVERSATION_ID)))
                .extracting(Message::getText)
                .containsExactly("What is URL monitoring?", "It checks a URL on a schedule.");
    }

    @Test
    void replaysOnlyTheHistoryThatPrecedesTheQuestion() {
        when(messages.findWindowBefore(anyLong(), anyLong(), anyInt())).thenReturn(List.of());

        memory.seed(CONVERSATION_ID, QUESTION_ID);

        // The question is sent as the prompt of the call, so replaying it here
        // would send it to the model twice.
        verify(messages).findWindowBefore(CONVERSATION_ID, QUESTION_ID, WINDOW);
    }

    @Test
    void forgetsThePreviousWindowBeforeReplaying() {
        chatMemory.add(ConversationMemory.key(CONVERSATION_ID), List.of(new UserMessage("stale question")));
        when(messages.findWindowBefore(CONVERSATION_ID, QUESTION_ID, WINDOW)).thenReturn(List.of());

        memory.seed(CONVERSATION_ID, QUESTION_ID);

        assertThat(chatMemory.get(ConversationMemory.key(CONVERSATION_ID))).isEmpty();
    }

    @Test
    void dropsTheMemoryOfAConversationThatNoLongerExists() {
        chatMemory.add(ConversationMemory.key(CONVERSATION_ID), List.of(new UserMessage("gone")));

        memory.onConversationsEvicted(new ConversationsEvictedEvent(List.of(CONVERSATION_ID)));

        assertThat(chatMemory.get(ConversationMemory.key(CONVERSATION_ID))).isEmpty();
    }

    @Test
    void boundsTheWindowByTheConfiguredMessageCount() {
        ConversationMemory small = new ConversationMemory(chatMemory, messages, properties(4));
        when(messages.findWindowBefore(anyLong(), anyLong(), anyInt())).thenReturn(List.of());

        small.seed(CONVERSATION_ID, QUESTION_ID);

        verify(messages).findWindowBefore(CONVERSATION_ID, QUESTION_ID, 4);
    }

    private static ChatMessage message(ChatRole role, String content) {
        ChatMessage message = new ChatMessage();
        message.setConversationId(CONVERSATION_ID);
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    private static AiProperties properties(int memoryMaxMessages) {
        return new AiProperties(
                true, "https://api.deepseek.com", "sk-test", "deepseek-flash",
                0.2, 1024, 2, 60, 10, memoryMaxMessages, 5);
    }
}
