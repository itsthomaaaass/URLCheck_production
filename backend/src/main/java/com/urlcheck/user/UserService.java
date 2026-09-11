package com.urlcheck.user;

import org.springframework.stereotype.Service;

@Service
public class UserService {

    private static final int MAX_USERNAME_CODE_POINTS = 50;

    private final UserMapper mapper;

    public UserService(UserMapper mapper) {
        this.mapper = mapper;
    }

    public User register(String username, String password) {
        validateUsername(username);
        validatePassword(password);

        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(UserPasswordHasher.encode(password));

        mapper.insert(user);
        return mapper.findById(user.getId());
    }

    public User authenticate(String username, String password) {
        if (username == null || password == null) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        User user = mapper.findByUsername(username);
        if (user == null || !UserPasswordHasher.matches(password, user.getPasswordHash())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        return user;
    }

    public User findById(Long id) {
        return mapper.findById(id);
    }

    private void validateUsername(String username) {
        if (username == null || username.isEmpty()) {
            throw new IllegalArgumentException("username 不能为空");
        }
        if (username.codePointCount(0, username.length()) > MAX_USERNAME_CODE_POINTS) {
            throw new IllegalArgumentException("username 最长 " + MAX_USERNAME_CODE_POINTS + " 个字符");
        }
        for (int offset = 0; offset < username.length();) {
            int codePoint = username.codePointAt(offset);
            boolean allowed = Character.isUnicodeIdentifierPart(codePoint)
                    || codePoint == 45
                    || codePoint == 46;
            if (!allowed) {
                throw new IllegalArgumentException("username 含有不支持的字符，请使用任意语言的字母、数字、_、- 或 .");
            }
            offset += Character.charCount(codePoint);
        }
    }

    private void validatePassword(String password) {
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("password 不能为空");
        }
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            boolean allowed = (c >= 97 && c <= 122)
                    || (c >= 65 && c <= 90)
                    || (c >= 48 && c <= 57)
                    || c == 95;
            if (!allowed) {
                throw new IllegalArgumentException("password 只能包含英文字母、数字或下划线");
            }
        }
    }
}
