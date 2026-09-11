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

    @Insert("INSERT INTO monitored_url (user_id, name, url, description) VALUES (#{userId}, #{name}, #{url}, #{description})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(MonitoredUrl monitoredUrl);

    @Select("SELECT id, user_id, name, url, description, created_at FROM monitored_url WHERE user_id = #{userId} ORDER BY id DESC")
    List<MonitoredUrl> findAllByUserId(Long userId);

    @Select("SELECT id, user_id, name, url, description, created_at FROM monitored_url WHERE id = #{id} AND user_id = #{userId}")
    MonitoredUrl findByIdAndUserId(Long id, Long userId);

    @Update("UPDATE monitored_url SET name = #{name}, url = #{url}, description = #{description} WHERE id = #{id} AND user_id = #{userId}")
    int update(MonitoredUrl monitoredUrl);

    @Delete("DELETE FROM monitored_url WHERE id = #{id} AND user_id = #{userId}")
    int deleteByIdAndUserId(Long id, Long userId);
}
