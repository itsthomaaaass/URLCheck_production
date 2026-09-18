package com.urlcheck.ai.message;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and appends the messages of a conversation.
 *
 * <p>Persistence only: this is the SQL-backed record, and it knows nothing about
 * what the model is shown. Deciding which messages become the model's context is
 * {@link com.urlcheck.ai.memory.ConversationMemory}'s job.
 */
@Service
public class ChatMessageService {

    private final ChatMessageMapper mapper;

    public ChatMessageService(ChatMessageMapper mapper) {
        this.mapper = mapper;
    }

    /** The complete history of one conversation, oldest first. */
    public List<ChatMessage> findAll(Long conversationId) {
        return mapper.findAllByConversationId(conversationId);
    }

    /**
     * At most {@code limit} messages of a conversation that precede
     * {@code beforeMessageId}, oldest first.
     *
     * <p>Conversation existence and ownership are the caller's responsibility:
     * a conversation with no messages simply yields an empty list.
     */
    public List<ChatMessage> findWindowBefore(Long conversationId, Long beforeMessageId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return mapper.findWindowBefore(conversationId, beforeMessageId, limit);
    }

    /**
     * Stores one message and returns it with its id filled in, which is the
     * order the conversation is read back in.
     */
    @Transactional
    public ChatMessage append(Long conversationId, ChatRole role, String content) {
        ChatMessage message = new ChatMessage();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        mapper.insert(message);
        return message;
    }
}
