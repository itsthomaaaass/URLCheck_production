-- 007: conversation history for the AI assistant.
--
-- Adds the two tables the assistant's conversation history lives in:
-- `conversation` (one chat thread, owned by one user) and `chat_message` (the
-- complete history of one conversation). Spring AI's ChatMemory is NOT stored
-- here: it is only the bounded window of recent context handed to the model, and
-- it is rebuilt from these tables, which stay the source of truth. The design is
-- in docs/ai_context_design.md.
--
-- Run once against the url_monitor database:
--   docker exec -i url-change-monitor-mysql mysql -uroot -proot < 007_ai_conversations.sql
--
-- Both statements are idempotent, so re-running the script is harmless.

USE url_monitor;

-- 1) conversation ---------------------------------------------------------
-- updated_at is the conversation's last activity, and it is what "the most
-- recent conversations" is measured by: an old thread the user returns to
-- becomes recent again. ConversationService keeps only the newest
-- app.ai.max-conversations rows per user, so the index is (user_id, updated_at).
CREATE TABLE IF NOT EXISTS conversation (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id    BIGINT UNSIGNED NOT NULL,
    title      VARCHAR(100)    NOT NULL,
    created_at DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_conversation_user_updated (user_id, updated_at),
    CONSTRAINT fk_conversation_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 2) chat_message ---------------------------------------------------------
-- One row per message, in insertion order. The id is the conversation order:
-- history is read back with ORDER BY id, so no timestamp tie-break is needed.
-- The cascade is what deletes a conversation's history with it, both when the
-- user deletes a conversation and when it is dropped by the retention cap.
CREATE TABLE IF NOT EXISTS chat_message (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT UNSIGNED NOT NULL,
    role            VARCHAR(16)     NOT NULL,
    content         TEXT            NOT NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_chat_message_conversation (conversation_id, id),
    CONSTRAINT fk_chat_message_conversation
        FOREIGN KEY (conversation_id) REFERENCES conversation (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
