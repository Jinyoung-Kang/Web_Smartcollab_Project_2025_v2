package com.smartcollab.user;

import com.smartcollab.global.config.AppProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * 관리자 계정 설정 (app.admin.*, ADR-0015). 아이디가 비어 있으면 관리자 계정을 쓰지 않습니다(기본값).
 *
 * @param username 관리자 아이디 (ADMIN_USERNAME)
 * @param password 관리자 비밀번호 (ADMIN_PASSWORD) — 앱이 켜질 때 해시해 저장하고, 바꾸면 다음 기동 때 반영합니다
 */
@ConfigurationProperties(prefix = "app.admin")
public record AdminProperties(String username, String password) {

    /** 공개 데모에서 관리자 비밀번호를 짐작하지 못하게 하는 최소 길이 */
    public static final int MIN_PASSWORD_LENGTH = 12;

    public AdminProperties {
        username = username == null ? "" : username.strip();
        password = password == null ? "" : password;
    }

    public boolean enabled() {
        return StringUtils.hasText(username);
    }

    /** 비밀번호를 가립니다 [L-4] */
    @Override
    public String toString() {
        return "AdminProperties[username=" + username + ", password=" + AppProperties.redact(password) + "]";
    }
}
