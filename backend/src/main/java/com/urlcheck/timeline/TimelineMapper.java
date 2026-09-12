package com.urlcheck.timeline;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** SQL for the {@code changes} table: appended and pruned by MonitorWriter. */
@Mapper
public interface TimelineMapper {

    /**
     * Appends one event, numbering it per URL in the same statement so two
     * writers cannot pick the same change_no; the unique index is the backstop.
     * check_id stays NULL: there is deliberately no row per check.
     */
    @Insert("INSERT INTO changes (url_id, check_id, change_no, detected_at, change_type,"
            + " old_hash, new_hash, http_status, error_type, response_time_ms)"
            + " SELECT #{urlId}, NULL, COALESCE(MAX(change_no), 0) + 1, NOW(6), #{changeType},"
            + " #{oldHash}, #{newHash}, #{httpStatus}, #{errorType}, #{responseTimeMs}"
            + " FROM changes WHERE url_id = #{urlId}")
    int insertEvent(long urlId, String changeType, String oldHash, String newHash,
                    Integer httpStatus, String errorType, long responseTimeMs);
    /** Newest first, so the client can render the list as it arrives. */
    @Select("SELECT id, change_no, detected_at, change_type, old_hash, new_hash,"
            + " http_status, error_type, response_time_ms FROM changes"
            + " WHERE url_id = #{urlId} ORDER BY change_no DESC LIMIT #{limit}")
    List<ChangeRow> findByUrlId(long urlId, int limit);

    /**
     * Keeps the first event of the URL (change_no = 1) and the newest
     * {@code keep} other rows, and deletes the rest. The derived table is
     * required: MySQL refuses to read the table it is deleting from unless the
     * subquery is materialised first.
     */
    @Delete("DELETE FROM changes WHERE url_id = #{urlId} AND change_no > 1"
            + " AND id NOT IN (SELECT id FROM (SELECT id FROM changes"
            + " WHERE url_id = #{urlId} AND change_no > 1"
            + " ORDER BY change_no DESC LIMIT #{keep}) keep_rows)")
    int prune(long urlId, int keep);
}