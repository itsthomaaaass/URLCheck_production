package com.urlcheck.ai.conversation;

import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.urlcheck.ai.config.AiEnabledCondition;
import com.urlcheck.ai.config.AiProperties;

/**
 * The lifecycle of one user's conversations: open, list, read, touch and delete.
 *
 * <p>Every method takes the signed-in user's id from the caller and every
 * statement is scoped by it, so a conversation id belonging to somebody else
 * does not resolve at all - the caller sees the same answer as for an id that
 * never existed.
 *
 * <p>Retention lives here, in {@link #enforceRetention}: a user keeps only the
 * newest {@code app.ai.max-conversations} conversations measured by last
 * activity. Opening a conversation and continuing one both go through it, so
 * there is one rule rather than two.
 *
 * <p>Absent when {@code app.ai.enabled} is false, like the endpoints that reach
 * it, so a deployment with the assistant off keeps no conversation store.
 */
@Service
@Conditional(AiEnabledCondition.class)
public class ConversationService {

    /** Title of a conversation that has no first message to be named after. */
    static final String UNTITLED = "新对话";

    /** Longest title taken from a first message, in code points. */
    private static final int TITLE_CODE_POINTS = 60;

    private final ConversationMapper mapper;
    private final ApplicationEventPublisher events;
    private final int maxConversations;

    public ConversationService(ConversationMapper mapper, ApplicationEventPublisher events,
            AiProperties properties) {
        this.mapper = mapper;
        this.events = events;
        // A cap below 1 would delete the conversation being opened.
        this.maxConversations = Math.max(1, properties.maxConversations());
    }

    /**
     * Opens a conversation, named after the message that starts it.
     *
     * <p>Naming it from the user's own words rather than from a summary keeps
     * the title something the user wrote, and costs no second provider call.
     *
     * @param firstMessage the opening message, or null when the client only
     *                     wants an empty thread to type into
     */
    @Transactional
    public Conversation create(Long userId, String firstMessage) {
        Conversation conversation = new Conversation();
        conversation.setUserId(userId);
        conversation.setTitle(titleOf(firstMessage));
        mapper.insert(conversation);
        return mapper.findByIdAndUserId(conversation.getId(), userId);
    }

    public List<Conversation> findAllForUser(Long userId) {
        return mapper.findAllByUserId(userId);
    }

    /**
     * The conversation, if it exists and belongs to this user.
     *
     * @throws NoSuchElementException when it is missing, or is somebody else's:
     *         the two are deliberately indistinguishable, so the endpoint does
     *         not confirm that another user's conversation exists
     */
    public Conversation requireOwned(Long userId, Long conversationId) {
        Conversation conversation = mapper.findByIdAndUserId(conversationId, userId);
        if (conversation == null) {
            throw new NoSuchElementException("会话不存在: id=" + conversationId);
        }
        return conversation;
    }

    /** Records activity, then holds the user to the retention cap. */
    @Transactional
    public void touch(Long userId, Long conversationId) {
        mapper.touch(conversationId, userId);
        enforceRetention(userId);
    }

    @Transactional
    public void delete(Long userId, Long conversationId) {
        requireOwned(userId, conversationId);
        mapper.deleteByIdAndUserId(conversationId, userId);
        events.publishEvent(new ConversationsEvictedEvent(List.of(conversationId)));
    }

    /**
     * Deletes this user's least recently used conversations until only
     * {@code app.ai.max-conversations} are left.
     *
     * <p>The messages of a dropped conversation go with it, through the cascade
     * on {@code chat_message.conversation_id}. The conversations that went are
     * announced so their memory can be discarded too.
     *
     * <p>Reached from both {@link #touch} and the endpoints that open a
     * conversation, so a client that never sends another message still holds to
     * the cap.
     */
    @Transactional
    public void enforceRetention(Long userId) {
        List<Long> evicted = mapper.findIdsBeyondRetention(userId, maxConversations);
        for (Long conversationId : evicted) {
            mapper.deleteByIdAndUserId(conversationId, userId);
        }
        if (!evicted.isEmpty()) {
            events.publishEvent(new ConversationsEvictedEvent(List.copyOf(evicted)));
        }
    }

    /**
     * Names a conversation after the message that started it.
     *
     * <p>Collapsed to one line and cut to a readable length: the title is a
     * handle in a list, not a summary, and a pasted multi-line question would
     * otherwise fill it.
     */
    private static String titleOf(String firstMessage) {
        if (!StringUtils.hasText(firstMessage)) {
            return UNTITLED;
        }
        String singleLine = firstMessage.strip().replaceAll("\\s+", " ");
        if (singleLine.codePointCount(0, singleLine.length()) <= TITLE_CODE_POINTS) {
            return singleLine;
        }
        int cut = singleLine.offsetByCodePoints(0, TITLE_CODE_POINTS);
        return singleLine.substring(0, cut) + "…";
    }
}
