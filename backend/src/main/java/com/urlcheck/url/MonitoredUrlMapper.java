package com.urlcheck.url;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MonitoredUrlMapper {

    String STATE_COLUMNS = "id, user_id, name, url, description, created_at, content_hash, "
            + "last_status, last_http_status, last_error_type, last_checked_at, "
            + "next_check_at, change_count, check_interval_seconds";

    /** next_check_at = NOW(6) so the scheduler picks the new URL up at once. */
    @Insert("INSERT INTO monitored_url (user_id, name, url, description, next_check_at) "
            + "VALUES (#{userId}, #{name}, #{url}, #{description}, NOW(6))")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(MonitoredUrl monitoredUrl);
    @Select("SELECT " + STATE_COLUMNS + " FROM monitored_url WHERE user_id = #{userId} ORDER BY id DESC")
    List<MonitoredUrl> findAllByUserId(Long userId);

    @Select("SELECT " + STATE_COLUMNS + " FROM monitored_url WHERE id = #{id} AND user_id = #{userId}")
    MonitoredUrl findByIdAndUserId(Long id, Long userId);

    @Update("UPDATE monitored_url SET name = #{name}, url = #{url}, description = #{description} "
            + "WHERE id = #{id} AND user_id = #{userId}")
    int update(MonitoredUrl monitoredUrl);
    /**
     * Called only when the user edits the URL string: the stored hash and the
     * timeline describe the old page, so both are discarded and a fresh
     * baseline is scheduled. The one write outside the scheduled checker.
     */
    @Update("UPDATE monitored_url SET content_hash = NULL, last_status = NULL, "
            + "last_http_status = NULL, last_error_type = NULL, last_checked_at = NULL, "
            + "change_count = 0, next_check_at = NOW(6) WHERE id = #{id} AND user_id = #{userId}")
    int resetMonitoringState(Long id, Long userId);

    @Delete("DELETE FROM changes WHERE url_id = #{urlId}")
    int deleteTimeline(Long urlId);

    @Delete("DELETE FROM monitored_url WHERE id = #{id} AND user_id = #{userId}")
    int deleteByIdAndUserId(Long id, Long userId);
}