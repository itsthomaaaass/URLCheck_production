package com.urlcheck.ai.conversation;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** SQL for {@code conversation}: opened, listed, touched, pruned and deleted. */
@Mapper
public interface ConversationMapper {

    String COLUMNS = "id, user_id, title, created_at, updated_at";

    @Insert("INSERT INTO conversation (user_id, title) VALUES (#{userId}, #{title})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Conversation conversation);

    /** Newest activity first: the order the client lists them in. */
    @Select("SELECT " + COLUMNS + " FROM conversation WHERE user_id = #{userId}"
            + " ORDER BY updated_at DESC, id DESC")
    List<Conversation> findAllByUserId(Long userId);

    /** Scoped by user, so another user's conversation simply does not resolve. */
    @Select("SELECT " + COLUMNS + " FROM conversation WHERE id = #{id} AND user_id = #{userId}")
    Conversation findByIdAndUserId(Long id, Long userId);

    /** Records activity, which is the order retention discards by. */
    @Update("UPDATE conversation SET updated_at = NOW(6) WHERE id = #{id} AND user_id = #{userId}")
    int touch(Long id, Long userId);

    @Delete("DELETE FROM conversation WHERE id = #{id} AND user_id = #{userId}")
    int deleteByIdAndUserId(Long id, Long userId);

    /**
     * The ids of the conversations past the newest {@code keep}, least recent
     * first, as the candidate list for retention.
     *
     * <p>MySQL has no {@code DELETE ... RETURNING}, so the rows to drop are read
     * first and then deleted by id. That also tells the caller whose model
     * memory to discard, which a plain DELETE could not.
     */
    @Select("SELECT id FROM conversation WHERE user_id = #{userId}"
            + " ORDER BY updated_at DESC, id DESC LIMIT #{keep}, 18446744073709551615")
    List<Long> findIdsBeyondRetention(Long userId, int keep);
}
