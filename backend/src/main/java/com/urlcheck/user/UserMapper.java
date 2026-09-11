package com.urlcheck.user;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {

    @Insert("INSERT INTO users (username, password_hash) VALUES (#{username}, #{passwordHash})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(User user);

    @Select("SELECT id, username, password_hash, created_at FROM users WHERE id = #{id}")
    User findById(Long id);

    @Select("SELECT id, username, password_hash, created_at FROM users WHERE username = #{username}")
    User findByUsername(String username);
}
