package com.urlcheck.ai.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;

import com.urlcheck.ai.conversation.Conversation;
import com.urlcheck.ai.conversation.ConversationService;
import com.urlcheck.ai.message.ChatMessage;
import com.urlcheck.ai.message.ChatMessageService;
import com.urlcheck.ai.message.ChatRole;
import com.urlcheck.user.SessionAttributes;
import com.urlcheck.web.NotLoggedInException;

/**
 * The conversation endpoints read the user from the session and let the service
 * decide what that user may see, so these cases pin the wiring and the refusal:
 * a conversation that is not the caller's answers 404 and its history is never
 * read.
 */
class ConversationControllerTest {

    private static final long USER_ID = 7L;
    private static final long CONVERSATION_ID = 101L;

    private final ConversationService conversations = mock(ConversationService.class);
    private final ChatMessageService messages = mock(ChatMessageService.class);
    private final ConversationController controller = new ConversationController(conversations, messages);

    @Test
    void opensAnEmptyConversationAndHoldsTheCap() {
        when(conversations.create(USER_ID, null)).thenReturn(conversation(CONVERSATION_ID, "新对话"));

        ConversationController.NewConversation created = controller.create(sessionFor(USER_ID));

        assertThat(created.id()).isEqualTo(CONVERSATION_ID);
        assertThat(created.title()).isEqualTo("新对话");
        // An empty conversation records no activity, so the cap is applied here.
        verify(conversations).enforceRetention(USER_ID);
    }

    @Test
    void listsThisUsersConversations() {
        when(conversations.findAllForUser(USER_ID)).thenReturn(List.of(
                conversation(102L, "Troubleshooting"), conversation(101L, "URL monitoring")));

        List<ConversationController.ConversationSummary> summaries = controller.list(sessionFor(USER_ID));

        assertThat(summaries).extracting(ConversationController.ConversationSummary::id)
                .containsExactly(102L, 101L);
        assertThat(summaries).extracting(ConversationController.ConversationSummary::title)
                .containsExactly("Troubleshooting", "URL monitoring");
    }

    @Test
    void returnsAConversationWithItsHistory() {
        when(conversations.requireOwned(USER_ID, CONVERSATION_ID))
                .thenReturn(conversation(CONVERSATION_ID, "URL monitoring"));
        when(messages.findAll(CONVERSATION_ID)).thenReturn(List.of(
                message(ChatRole.USER, "What is URL monitoring?"),
                message(ChatRole.ASSISTANT, "It checks a URL on a schedule.")));

        ConversationController.ConversationDetail detail = controller.get(CONVERSATION_ID, sessionFor(USER_ID));

        assertThat(detail.id()).isEqualTo(CONVERSATION_ID);
        assertThat(detail.title()).isEqualTo("URL monitoring");
        assertThat(detail.messages()).extracting(ConversationController.MessageView::role)
                .containsExactly("USER", "ASSISTANT");
        assertThat(detail.messages()).extracting(ConversationController.MessageView::content)
                .containsExactly("What is URL monitoring?", "It checks a URL on a schedule.");
    }

    @Test
    void answers404ForAConversationThatIsNotTheUsers() {
        when(conversations.requireOwned(USER_ID, CONVERSATION_ID))
                .thenThrow(new NoSuchElementException("会话不存在: id=" + CONVERSATION_ID));

        assertThatThrownBy(() -> controller.get(CONVERSATION_ID, sessionFor(USER_ID)))
                .isInstanceOf(NoSuchElementException.class);
        verify(messages, never()).findAll(anyLong());
    }

    @Test
    void deletesAConversationItsOwnerAskedFor() {
        ResponseEntity<Void> response = controller.delete(CONVERSATION_ID, sessionFor(USER_ID));

        verify(conversations).delete(USER_ID, CONVERSATION_ID);
        assertThat(response.getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void requiresALoggedInUser() {
        assertThatThrownBy(() -> controller.list(new MockHttpSession()))
                .isInstanceOf(NotLoggedInException.class);
    }

    private static Conversation conversation(long id, String title) {
        Conversation conversation = new Conversation();
        conversation.setId(id);
        conversation.setUserId(USER_ID);
        conversation.setTitle(title);
        conversation.setUpdatedAt(LocalDateTime.now());
        return conversation;
    }

    private static ChatMessage message(ChatRole role, String content) {
        ChatMessage message = new ChatMessage();
        message.setConversationId(CONVERSATION_ID);
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    private static MockHttpSession sessionFor(long userId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionAttributes.USER_ID, userId);
        return session;
    }
}
