package com.urlcheck.ai.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.urlcheck.ai.AiUnavailableException;
import com.urlcheck.ai.agent.AiAgent;
import com.urlcheck.ai.config.AiEnabledCondition;
import com.urlcheck.ai.conversation.Conversation;
import com.urlcheck.ai.conversation.ConversationService;
import com.urlcheck.ai.deletion.PendingDeletions;
import com.urlcheck.ai.message.ChatMessage;
import com.urlcheck.ai.message.ChatMessageService;
import com.urlcheck.ai.message.ChatRole;
import com.urlcheck.url.MonitoredUrlService;
import com.urlcheck.web.SessionUser;

/**
 * The assistant's HTTP entry point.
 *
 * <p>Thin by design: it resolves the user from the session, records the turn in
 * the conversation's history, hands the message to the agent, and turns a
 * proposed deletion into a confirmation the frontend can render. No prompting
 * and no URL rules live here.
 *
 * <p>The endpoints are absent when {@code app.ai.enabled} is false, so a
 * deployment with no AI key exposes nothing under {@code /api/ai}.
 * Authentication failures and validation errors are mapped by
 * {@link com.urlcheck.web.ApiExceptionHandler}.
 */
@RestController
@RequestMapping("/api/ai")
@Conditional(AiEnabledCondition.class)
public class AiController {

    private static final Logger log = LoggerFactory.getLogger(AiController.class);

    private final AiAgent agent;
    private final MonitoredUrlService urlService;
    private final PendingDeletions pendingDeletions;
    private final ConversationService conversations;
    private final ChatMessageService messages;

    public AiController(AiAgent agent, MonitoredUrlService urlService, PendingDeletions pendingDeletions,
            ConversationService conversations, ChatMessageService messages) {
        this.agent = agent;
        this.urlService = urlService;
        this.pendingDeletions = pendingDeletions;
        this.conversations = conversations;
        this.messages = messages;
    }

    /**
     * One message, and optionally the confirmation of a deletion the assistant
     * proposed in an earlier answer.
     *
     * <p>{@code conversationId} names the thread to continue. Left out, the
     * message starts a new conversation, and the answer reports which one.
     */
    public record ChatRequest(Long conversationId, String message, String confirmationToken) {
    }

    /** One URL a confirmation would remove. */
    public record PendingUrl(long id, String name, String url) {
    }

    /**
     * What the user still has to confirm before anything is deleted. Absent when
     * the answer needs no confirmation.
     */
    public record Confirmation(String token, List<PendingUrl> urls) {
    }

    /**
     * One answer, the conversation it belongs to, and anything the answer wants
     * confirmed first.
     *
     * <p>{@code conversationId} is how a client that sent no id learns which
     * conversation its message started, and continues it next time.
     */
    public record ChatResponse(Long conversationId, String message, Confirmation confirmation) {
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request, HttpSession session) {
        Long userId = SessionUser.requireUserId(session);

        if (StringUtils.hasText(request.confirmationToken())) {
            return confirm(request, userId, session);
        }
        if (!StringUtils.hasText(request.message())) {
            throw new IllegalArgumentException("message 不能为空");
        }

        Conversation conversation = openConversation(userId, request);
        // Stored before the model is called, so the question is not lost if the
        // provider fails, and so the history reads in the order it was typed.
        ChatMessage question = messages.append(conversation.getId(), ChatRole.USER, request.message());
        conversations.touch(userId, conversation.getId());

        AiAgent.AgentReply reply = agent.chat(
                userId, conversation.getId(), question.getId(), request.message());
        messages.append(conversation.getId(), ChatRole.ASSISTANT, reply.message());

        if (reply.proposedDeletions().isEmpty()) {
            return new ChatResponse(conversation.getId(), reply.message(), null);
        }

        PendingDeletions.Batch batch = pendingDeletions.open(session, reply.proposedDeletions());
        List<PendingUrl> urls = batch.items().stream()
                .map(item -> new PendingUrl(item.urlId(), item.name(), item.url()))
                .toList();
        return new ChatResponse(conversation.getId(), reply.message(), new Confirmation(batch.token(), urls));
    }

    /**
     * The conversation this message belongs to: the one the client named, or a
     * new one opened because it named none.
     *
     * <p>Naming somebody else's conversation is answered with a 404, the same as
     * naming one that does not exist, because
     * {@link ConversationService#requireOwned} scopes the lookup by user.
     */
    private Conversation openConversation(Long userId, ChatRequest request) {
        if (request.conversationId() == null) {
            return conversations.create(userId, request.message());
        }
        return conversations.requireOwned(userId, request.conversationId());
    }

    /**
     * Carries out a deletion the user confirmed.
     *
     * <p>This does not go back to the model: the ids come from the batch the
     * session is holding, so the outcome is deterministic, costs no second
     * provider call, and cannot be steered by anything the model or the client
     * sends back.
     */
    private ChatResponse confirm(ChatRequest request, Long userId, HttpSession session) {
        PendingDeletions.Batch batch = pendingDeletions.take(session, request.confirmationToken())
                .orElseThrow(() -> new IllegalArgumentException("确认已过期，请重新发起删除请求"));

        List<String> removed = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (PendingDeletions.Item item : batch.items()) {
            try {
                urlService.delete(userId, item.urlId());
                removed.add(item.name());
            } catch (NoSuchElementException gone) {
                missing.add(item.name());
            }
        }
        return new ChatResponse(request.conversationId(), confirmationMessage(removed, missing), null);
    }

    private static String confirmationMessage(List<String> removed, List<String> missing) {
        StringBuilder message = new StringBuilder();
        if (removed.isEmpty()) {
            message.append("没有删除任何 URL。");
        } else {
            message.append("已删除 ")
                    .append(removed.size())
                    .append(" 个 URL：")
                    .append(String.join("、", removed))
                    .append("。");
        }
        if (!missing.isEmpty()) {
            message.append("未找到：").append(String.join("、", missing)).append("。");
        }
        return message.toString();
    }

    /**
     * The provider is unreachable or unusable. The reason is logged, not
     * returned: it can name the provider and the model, which is not the user's
     * business.
     */
    @ExceptionHandler(AiUnavailableException.class)
    public ResponseEntity<Map<String, String>> handleUnavailable(AiUnavailableException ex) {
        log.warn("AI assistant call failed", ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("message", "AI 助手暂时不可用，请稍后再试"));
    }
}
