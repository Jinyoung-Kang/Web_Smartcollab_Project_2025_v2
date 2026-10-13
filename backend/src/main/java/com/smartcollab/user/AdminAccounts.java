package com.smartcollab.user;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 관리자 계정 판별 (ADR-0015).
 * <p>역할이 ADMIN 이어도 <b>지금 설정된 아이디(ADMIN_USERNAME)와 같을 때만</b> 관리자로 인정합니다. 아이디를 바꾸거나 설정을 지우면
 * 예전 관리자 계정은 로그인도 관리 API 도 쓸 수 없습니다 — 설정을 지우는 것만으로 관리 기능을 끌 수 있게 하기 위함입니다.</p>
 */
@Component
@RequiredArgsConstructor
public class AdminAccounts {

    private final AdminProperties props;
    private final UserRepository users;

    public boolean isActiveAdmin(User user) {
        return user != null && user.isAdmin() && props.enabled()
                && user.getUsername().toLowerCase(Locale.ROOT).equals(props.username().toLowerCase(Locale.ROOT));
    }

    public boolean isActiveAdmin(Long userId) {
        return userId != null && users.findById(userId).filter(this::isActiveAdmin).isPresent();
    }
}
