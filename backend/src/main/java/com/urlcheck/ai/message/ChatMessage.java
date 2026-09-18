package com.urlcheck.ai.message;

import java.time.LocalDateTime;

/**
 * One message of a conversation, as stored in {@code chat_message}.
 *
 * <p>Together these rows are the complete history the user reads. They are not
 * what the model is sent: {@link com.urlcheck.ai.memory.ConversationMemory}
 * replays a bounded window of them.
 */
public class ChatMessage {

    private Long id;
    private Long conversationId;
    private ChatRole role;
    private String content;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public void setConversationId(Long conversationId) {
        this.conversationId = conversationId;
    }

    public ChatRole getRole() {
        return role;
    }

    public void setRole(ChatRole role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
