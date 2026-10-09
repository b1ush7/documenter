package com.documenter.util;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class BcryptUtil {
    private static final int BCRYPT_STRENGTH = 11;

    private static final BCryptPasswordEncoder bcryptPasswordEncoder = new BCryptPasswordEncoder(BCRYPT_STRENGTH);

    public static String encode(String password) {
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        return bcryptPasswordEncoder.encode(password);
    }

    public static boolean check(String password, String encodedPassword) {
        return bcryptPasswordEncoder.matches(password, encodedPassword);
    }
}
