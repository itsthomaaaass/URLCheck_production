package com.urlcheck.monitor;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.urlcheck.url.MonitoredUrl;
import com.urlcheck.url.MonitoredUrlMapper;

/**
 * The scheduled checker's own database access.
 *
 * <p>Kept apart from {@link MonitoredUrlMapper} on purpose: the manual check
 * path only ever sees that read-only mapper, so it cannot reach the writes
 * below even by accident.
 */
@Mapper
public interface MonitorMapper {

    /** URLs whose next_check_at has passed, or that have never been checked. */
    @Select("SELECT " + MonitoredUrlMapper.STATE_COLUMNS
            + " FROM monitored_url"
            + " WHERE next_check_at IS NULL OR next_check_at <= NOW(6)"
            + " ORDER BY COALESCE(next_check_at, '1970-01-01 00:00:00'), id"
            + " LIMIT #{limit}")
    List<MonitoredUrl> findDue(long limit);
    /**
     * Moves next_check_at forward and reports whether this caller won the race.
     * The interval is the URL's own override, or the environment default when
     * it has none, and the whole comparison happens on the database clock so a
     * host in another time zone cannot shift it.
     */
    @Update("UPDATE monitored_url SET next_check_at ="
            + " DATE_ADD(NOW(6), INTERVAL COALESCE(check_interval_seconds, #{defaultInterval}) SECOND)"
            + " WHERE id = #{id} AND (next_check_at IS NULL OR next_check_at <= NOW(6))")
    int claim(long id, long defaultInterval);

    /** Everything the change decision needs, and nothing else. */
    @Select("SELECT id, user_id, url, content_hash, last_status, last_http_status, last_error_type"
            + " FROM monitored_url WHERE id = #{id}")
    MonitoredUrl findCheckState(long id);
    /** content_hash is only ever replaced by a successful probe. */
    @Update("UPDATE monitored_url SET content_hash = #{contentHash}, last_status = 'UP',"
            + " last_http_status = #{httpStatus}, last_error_type = NULL, last_checked_at = NOW(6),"
            + " change_count = change_count + #{added} WHERE id = #{id}")
    int markUp(long id, String contentHash, Integer httpStatus, int added);

    /** Keeps the last good hash, so a recovery still has something to compare. */
    @Update("UPDATE monitored_url SET last_status = 'DOWN', last_http_status = #{httpStatus},"
            + " last_error_type = #{errorType}, last_checked_at = NOW(6),"
            + " change_count = change_count + #{added} WHERE id = #{id}")
    int markDown(long id, Integer httpStatus, String errorType, int added);

    /** Retries sooner when a check failed for a reason we did not expect. */
    @Update("UPDATE monitored_url SET next_check_at = DATE_ADD(NOW(6), INTERVAL 300 SECOND)"
            + " WHERE id = #{id} AND next_check_at > DATE_ADD(NOW(6), INTERVAL 300 SECOND)")
    int retrySoon(long id);
}