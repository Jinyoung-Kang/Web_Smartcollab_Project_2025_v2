package com.smartcollab.user;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 계정을 환경 변수(ADMIN_USERNAME·ADMIN_PASSWORD)로 준비합니다 (ADR-0015).
 * <ul>
 *   <li>아이디가 없으면 아무것도 하지 않습니다(관리 기능 꺼짐).</li>
 *   <li>아이디가 있는데 비밀번호가 짧거나 없으면 기동을 멈춥니다 — 공개 데모에서 약한 관리자 비밀번호로 켜지는 일을 막습니다.</li>
 *   <li>계정이 없으면 만들고(드라이브·팀 없음), 있으면 비밀번호가 바뀌었을 때만 새 해시로 바꿉니다.</li>
 *   <li>같은 아이디의 <b>일반 계정이 이미 있으면 관리자로 올리지 않습니다</b>(오류 로그만 남기고 관리 기능은 꺼진 채로 기동) —
 *       누군가 먼저 그 아이디로 가입해 두었을 때 그 사람이 관리자가 되는 것을 막습니다.</li>
 * </ul>
 * <p>비밀번호는 해시만 저장하고 로그에 남기지 않습니다. 코드·저장소에는 어떤 관리자 비밀번호도 없습니다.</p>
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class AdminAccountInitializer implements ApplicationRunner {

    static final String NAME = "관리자";

    private final AdminProperties props;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!props.enabled()) {
            return;
        }
        if (props.password().length() < AdminProperties.MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException("ADMIN_USERNAME 을 쓰려면 ADMIN_PASSWORD 가 " + AdminProperties.MIN_PASSWORD_LENGTH
                    + "자 이상이어야 합니다.");
        }
        // 로그인은 가입 아이디 규칙에 맞지 않는 아이디를 조회 없이 거절하므로, 규칙에 맞지 않으면 계정은 만들어져도 로그인할 수 없었습니다 [QA6-09]
        if (!props.username().matches(User.USERNAME_REGEX) || DemoAccounts.isReserved(props.username())) {
            throw new IllegalStateException("ADMIN_USERNAME 은 영문·숫자·_ . - 조합 4~20자이고 체험 계정 아이디가 아니어야 합니다.");
        }
        User existing = users.findByUsername(props.username()).orElse(null);
        if (existing == null) {
            users.save(new User(props.username(), passwordEncoder.encode(props.password()), NAME, null, Role.ADMIN));
            log.info("Admin account '{}' created", props.username());
        } else if (!existing.isAdmin()) {
            log.error("ADMIN_USERNAME '{}' is already used by a regular account; admin features stay disabled", props.username());
        } else if (!passwordEncoder.matches(props.password(), existing.getPassword())) {
            existing.changePassword(passwordEncoder.encode(props.password()));
            log.info("Admin account '{}' password updated from ADMIN_PASSWORD", props.username());
        }
    }
}
