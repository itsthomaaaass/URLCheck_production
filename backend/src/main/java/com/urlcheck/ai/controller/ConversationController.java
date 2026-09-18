package com.urlcheck.ai.controller;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.servlet.http.HttpSession;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.urlcheck.ai.config.AiEnabledCondition;
import com.urlcheck.ai.conversation.Conversation;
import com.urlcheck.ai.conversation.ConversationService;
import com.urlcheck.ai.message.ChatMessage;
import com.urlcheck.ai.message.ChatMessageService;
import com.urlcheck.web.SessionUser;

/**
 * The signed-in user's conversation history.
 *
 * <p>Thin by design: the user comes from the session and is never taken from the
 * request, ownership is checked by {@link ConversationService}, and no SQL or
 * prompting lives here.
 *
 * <p>Absent when {@code app.ai.enabled} is false, exactly like the chat endpoint,
 * so a deployment with the assistant off exposes none of this.
 */
@RestController
@RequestMapping("/api/ai/conversations")
@Conditional(AiEnabledCondition.class)
public class ConversationController {

    private final ConversationService conversations;
    private final ChatMessageService messages;

    public ConversationController(ConversationService conversations, ChatMessageService messages) {
        this.conversations = conversations;
        this.messages = messages;
    }

    /** One conversation, as the list shows it. */
    public record ConversationSummary(long id, String title, LocalDateTime updatedAt) {
    }

    /** One stored message of a conversation. */
    public record MessageView(String role, String content) {
    }

    /** One conversation including its history, oldest message first. */
    public record ConversationDetail(long id, String title, List<MessageView> messages) {
    }

    /** The conversation that was opened. */
    public record NewConversation(long id, String title) {
    }

    /**
     * Opens an empty conversation. The chat endpoint opens one by itself when a
     * message arrives without an id, so this is only for a client that wants the
     * thread to exist before the user has typed anything.
     */
    @PostMapping
    public NewConversation create(HttpSession session) {
        Long userId = SessionUser.requireUserId(session);
        Conversation created = conversations.create(userId, null);
        // An empty conversation has no message to record activity through, so
        // the cap is applied here instead.
        conversations.enforceRetention(userId);
        return new NewConversation(created.getId(), created.getTitle());
    }

    /** This user's conversations, most recent activity first. */
    @GetMapping
    public List<ConversationSummary> list(HttpSession session) {
        Long userId = SessionUser.requireUserId(session);
        return conversations.findAllForUser(userId).stream()
                .map(conversation -> new ConversationSummary(
                        conversation.getId(), conversation.getTitle(), conversation.getUpdatedAt()))
                .toList();
    }

    /** One conversation and its history. Not this user's conversation: 404. */
    @GetMapping("/{id}")
    public ConversationDetail get(@PathVariable long id, HttpSession session) {
        Long userId = SessionUser.requireUserId(session);
        Conversation conversation = conversations.requireOwned(userId, id);
        List<MessageView> history = messages.findAll(conversation.getId()).stream()
                .map(message -> new MessageView(message.getRole().name(), message.getContent()))
                .toList();
        return new ConversationDetail(conversation.getId(), conversation.getTitle(), history);
    }

    /** Deletes a conversation and its messages. Not this user's: 404. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id, HttpSession session) {
        Long userId = SessionUser.requireUserId(session);
        conversations.delete(userId, id);
        return ResponseEntity.noContent().build();
    }
}
