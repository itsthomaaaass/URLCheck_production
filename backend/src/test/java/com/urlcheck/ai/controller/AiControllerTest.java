package com.urlcheck.ai.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;

import com.urlcheck.ai.AiUnavailableException;
import com.urlcheck.ai.agent.AiAgent;
import com.urlcheck.ai.conversation.Conversation;
import com.urlcheck.ai.conversation.ConversationService;
import com.urlcheck.ai.deletion.PendingDeletions;
import com.urlcheck.ai.message.ChatMessage;
import com.urlcheck.ai.message.ChatMessageService;
import com.urlcheck.ai.message.ChatRole;
import com.urlcheck.url.MonitoredUrlService;
import com.urlcheck.user.SessionAttributes;
import com.urlcheck.web.NotLoggedInException;

/**
 * Covers the confirmation handshake, and the conversation each turn is recorded
 * in, without a provider: a proposed deletion is only recorded until the user
 * confirms it, and the ids a confirmation acts on come from the session rather
 * than from the request.
 */
class AiControllerTest {

    private static final long USER_ID = 7L;
    private static final long CONVERSATION_ID = 101L;
    private static final long QUESTION_ID = 1000L;

    private final AiAgent agent = mock(AiAgent.class);
    private final MonitoredUrlService urlService = mock(MonitoredUrlService.class);
    private final PendingDeletions pendingDeletions = new PendingDeletions();
    private final ConversationService conversations = mock(ConversationService.class);
    private final ChatMessageService messages = mock(ChatMessageService.class);
    private final AiController controller =
            new AiController(agent, urlService, pendingDeletions, conversations, messages);

    @Test
    void answersWithoutAConfirmationWhenNothingIsBeingDeleted() {
        givenATurn("show my urls", "You monitor 2 URLs.", List.of());

        AiController.ChatResponse response = ask("show my urls");

        assertThat(response.message()).isEqualTo("You monitor 2 URLs.");
        assertThat(response.confirmation()).isNull();
    }

    @Test
    void reportsTheConversationTheMessageStarted() {
        givenATurn("show my urls", "You monitor 2 URLs.", List.of());

        assertThat(ask("show my urls").conversationId()).isEqualTo(CONVERSATION_ID);
    }

    @Test
    void recordsBothSidesOfTheTurnInTheConversation() {
        givenATurn("show my urls", "You monitor 2 URLs.", List.of());

        ask("show my urls");

        verify(messages).append(CONVERSATION_ID, ChatRole.USER, "show my urls");
        verify(agent).chat(USER_ID, CONVERSATION_ID, QUESTION_ID, "show my urls");
        verify(messages).append(CONVERSATION_ID, ChatRole.ASSISTANT, "You monitor 2 URLs.");
        verify(conversations).touch(USER_ID, CONVERSATION_ID);
    }

    @Test
    void continuesTheConversationTheClientNamed() {
        when(conversations.requireOwned(USER_ID, CONVERSATION_ID)).thenReturn(conversation());
        when(messages.append(CONVERSATION_ID, ChatRole.USER, "and now?"))
                .thenReturn(storedQuestion("and now?"));
        when(agent.chat(USER_ID, CONVERSATION_ID, QUESTION_ID, "and now?"))
                .thenReturn(new AiAgent.AgentReply("Nothing changed.", List.of()));

        AiController.ChatResponse response =
                controller.chat(new AiController.ChatRequest(CONVERSATION_ID, "and now?", null), sessionFor(USER_ID));

        assertThat(response.conversationId()).isEqualTo(CONVERSATION_ID);
        assertThat(response.message()).isEqualTo("Nothing changed.");
        // An existing thread is continued, not replaced.
        verify(conversations, never()).create(anyLong(), anyString());
    }

    @Test
    void refusesAConversationBelongingToSomebodyElse() {
        when(conversations.requireOwned(USER_ID, CONVERSATION_ID))
                .thenThrow(new NoSuchElementException("会话不存在: id=" + CONVERSATION_ID));

        assertThatThrownBy(() -> controller.chat(
                new AiController.ChatRequest(CONVERSATION_ID, "hello", null), sessionFor(USER_ID)))
                .isInstanceOf(NoSuchElementException.class);
        verify(agent, never()).chat(anyLong(), anyLong(), anyLong(), anyString());
    }

    /**
     * The question is stored before the model is called, so a provider outage
     * costs the answer, not what the user typed.
     */
    @Test
    void keepsTheQuestionWhenTheProviderFails() {
        when(conversations.create(USER_ID, "show my urls")).thenReturn(conversation());
        when(messages.append(CONVERSATION_ID, ChatRole.USER, "show my urls"))
                .thenReturn(storedQuestion("show my urls"));
        when(agent.chat(USER_ID, CONVERSATION_ID, QUESTION_ID, "show my urls"))
                .thenThrow(new AiUnavailableException("AI provider call failed", new IllegalStateException("401")));

        assertThatThrownBy(() -> ask("show my urls")).isInstanceOf(AiUnavailableException.class);

        verify(messages).append(CONVERSATION_ID, ChatRole.USER, "show my urls");
        verify(messages, never()).append(CONVERSATION_ID, ChatRole.ASSISTANT, "show my urls");
    }

    @Test
    void asksForConfirmationInsteadOfDeleting() {
        givenATurn("delete Example", "Shall I delete Example?",
                List.of(new PendingDeletions.Item(3L, "Example", "https://example.com")));

        AiController.ChatResponse response = ask("delete Example");

        assertThat(response.confirmation()).isNotNull();
        assertThat(response.confirmation().token()).isNotBlank();
        assertThat(response.confirmation().urls())
                .containsExactly(new AiController.PendingUrl(3L, "Example", "https://example.com"));
        verify(urlService, never()).delete(anyLong(), anyLong());
    }

    @Test
    void deletesOnlyTheConfirmedUrlsAndConsumesTheToken() {
        givenATurn("delete Example", "Shall I delete Example?",
                List.of(new PendingDeletions.Item(3L, "Example", "https://example.com")));
        MockHttpSession session = sessionFor(USER_ID);
        String token = controller.chat(
                new AiController.ChatRequest(null, "delete Example", null), session).confirmation().token();

        AiController.ChatResponse response = controller.chat(
                new AiController.ChatRequest(null, null, token), session);

        assertThat(response.message()).contains("Example");
        assertThat(response.confirmation()).isNull();
        verify(urlService).delete(USER_ID, 3L);
        assertThatThrownBy(() -> controller.chat(new AiController.ChatRequest(null, null, token), session))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cannotConfirmAnotherSessionsProposal() {
        givenATurn("delete Example", "Shall I delete Example?",
                List.of(new PendingDeletions.Item(3L, "Example", "https://example.com")));
        String token = controller.chat(
                new AiController.ChatRequest(null, "delete Example", null), sessionFor(USER_ID))
                .confirmation().token();

        assertThatThrownBy(() -> controller.chat(
                new AiController.ChatRequest(null, null, token), sessionFor(8L)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(urlService, never()).delete(anyLong(), anyLong());
    }

    @Test
    void reportsAUrlThatDisappearedBeforeTheConfirmation() {
        givenATurn("delete Example", "Shall I delete Example?",
                List.of(new PendingDeletions.Item(3L, "Example", "https://example.com")));
        when(urlService.delete(USER_ID, 3L)).thenThrow(new NoSuchElementException("URL 不存在: id=3"));
        MockHttpSession session = sessionFor(USER_ID);
        String token = controller.chat(
                new AiController.ChatRequest(null, "delete Example", null), session).confirmation().token();

        AiController.ChatResponse response = controller.chat(
                new AiController.ChatRequest(null, null, token), session);

        assertThat(response.message()).contains("未找到").contains("Example");
    }

    @Test
    void rejectsAnEmptyMessage() {
        assertThatThrownBy(() -> controller.chat(
                new AiController.ChatRequest(null, "  ", null), sessionFor(USER_ID)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresALoggedInUser() {
        assertThatThrownBy(() -> controller.chat(
                new AiController.ChatRequest(null, "hello", null), new MockHttpSession()))
                .isInstanceOf(NotLoggedInException.class);
    }

    @Test
    void reports503WhenTheProviderIsUnusable() {
        ResponseEntity<Map<String, String>> response = controller.handleUnavailable(
                new AiUnavailableException("AI provider call failed", new IllegalStateException("401")));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).containsKey("message");
    }

    /** Stubs one whole turn: a new conversation, the question, and the answer. */
    private void givenATurn(String question, String answer, List<PendingDeletions.Item> deletions) {
        when(conversations.create(USER_ID, question)).thenReturn(conversation());
        when(messages.append(CONVERSATION_ID, ChatRole.USER, question)).thenReturn(storedQuestion(question));
        when(agent.chat(USER_ID, CONVERSATION_ID, QUESTION_ID, question))
                .thenReturn(new AiAgent.AgentReply(answer, deletions));
    }

    private AiController.ChatResponse ask(String message) {
        return controller.chat(new AiController.ChatRequest(null, message, null), sessionFor(USER_ID));
    }

    private static Conversation conversation() {
        Conversation conversation = new Conversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setUserId(USER_ID);
        conversation.setTitle("show my urls");
        return conversation;
    }

    private static ChatMessage storedQuestion(String content) {
        ChatMessage message = new ChatMessage();
        message.setId(QUESTION_ID);
        message.setConversationId(CONVERSATION_ID);
        message.setRole(ChatRole.USER);
        message.setContent(content);
        return message;
    }

    private static MockHttpSession sessionFor(long userId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionAttributes.USER_ID, userId);
        return session;
    }
}
