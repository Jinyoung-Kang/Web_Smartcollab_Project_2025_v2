package com.smartcollab.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 관리자 계정을 환경 변수로 준비하는 규칙 (ADR-0015) */
class AdminAccountInitializerTest {

    private static final String PASSWORD = "admin-password-for-tests";

    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);

    private AdminAccountInitializer initializer(String username, String password) {
        return new AdminAccountInitializer(new AdminProperties(username, password), users, encoder);
    }

    @Test
    @DisplayName("[ADM-10] 아이디를 설정하지 않으면 아무것도 하지 않는다 (관리 기능 꺼짐)")
    void disabledWithoutUsername() {
        initializer("", PASSWORD).run(null);

        verifyNoInteractions(users);
    }

    @Test
    @DisplayName("[ADM-10] 비밀번호가 12자보다 짧거나 체험 계정 아이디면 기동을 멈춘다")
    void rejectsWeakConfiguration() {
        assertThatThrownBy(() -> initializer("admin", "short-pass").run(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> initializer("admin", "").run(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> initializer("demo1", PASSWORD).run(null)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(users);
    }

    @Test
    @DisplayName("[ADM-10] 계정이 없으면 비밀번호 해시만 저장한 관리자 계정을 만든다")
    void createsAdmin() {
        when(users.findByUsername("admin")).thenReturn(Optional.empty());
        when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        initializer("admin", PASSWORD).run(null);

        verify(users).save(argThat(u -> u.isAdmin() && "admin".equals(u.getUsername())
                && !PASSWORD.equals(u.getPassword()) && encoder.matches(PASSWORD, u.getPassword())));
    }

    @Test
    @DisplayName("[ADM-10] 같은 아이디의 일반 계정이 이미 있으면 관리자로 올리지 않는다")
    void doesNotPromoteRegularAccount() {
        User regular = new User("admin", encoder.encode("someone-else-pw"), "먼저 가입한 사람", null, Role.USER);
        when(users.findByUsername("admin")).thenReturn(Optional.of(regular));

        initializer("admin", PASSWORD).run(null);

        assertThat(regular.getRole()).isEqualTo(Role.USER);
        assertThat(encoder.matches(PASSWORD, regular.getPassword())).isFalse();
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("[ADM-10] 관리자 비밀번호를 바꾸면 다음 기동 때 새 해시로 바뀌고, 같으면 그대로 둔다")
    void rotatesPassword() {
        User admin = new User("admin", encoder.encode("old-admin-password"), "관리자", null, Role.ADMIN);
        when(users.findByUsername("admin")).thenReturn(Optional.of(admin));

        initializer("admin", PASSWORD).run(null);
        String rotated = admin.getPassword();
        initializer("admin", PASSWORD).run(null);

        assertThat(encoder.matches(PASSWORD, rotated)).isTrue();
        assertThat(admin.getPassword()).isEqualTo(rotated);
    }
}
