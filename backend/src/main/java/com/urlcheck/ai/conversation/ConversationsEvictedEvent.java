package com.urlcheck.ai.conversation;

import java.util.List;

/**
 * Conversations that no longer exist, either deleted by the user or dropped by
 * the retention cap.
 *
 * <p>Published so that the conversational memory held for them can be
 * discarded: a conversation that is gone must not keep answering through a
 * window that outlived its row. Ids are carried rather than the rows themselves,
 * because nothing needs the deleted conversations back.
 */
public record ConversationsEvictedEvent(List<Long> conversationIds) {
}
