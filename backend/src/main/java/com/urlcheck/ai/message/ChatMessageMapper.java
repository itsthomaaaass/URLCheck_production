package com.urlcheck.ai.message;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

/** SQL for {@code chat_message}: the assistant's persistent history. */
@Mapper
public interface ChatMessageMapper {

    String COLUMNS = "id, conversation_id, role, content, created_at";

    @Insert("INSERT INTO chat_message (conversation_id, role, content)"
            + " VALUES (#{conversationId}, #{role}, #{content})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ChatMessage message);

    /**
     * The complete history of one conversation, oldest first. Ids are handed out
     * in insertion order, so id order is conversation order and no timestamp
     * tie-break is needed.
     */
    @Select("SELECT " + COLUMNS + " FROM chat_message WHERE conversation_id = #{conversationId}"
            + " ORDER BY id ASC")
    List<ChatMessage> findAllByConversationId(Long conversationId);

    /**
     * The newest {@code limit} messages older than {@code beforeId}, oldest
     * first.
     *
     * <p>The inner query takes the tail newest-first so the
     * {@code (conversation_id, id)} index can be walked backwards, and the outer
     * one puts that window back into reading order for the caller.
     */
    @Select("SELECT " + COLUMNS + " FROM (SELECT " + COLUMNS + " FROM chat_message"
            + " WHERE conversation_id = #{conversationId} AND id < #{beforeId}"
            + " ORDER BY id DESC LIMIT #{limit}) window_rows ORDER BY id ASC")
    List<ChatMessage> findWindowBefore(Long conversationId, Long beforeId, int limit);
}
