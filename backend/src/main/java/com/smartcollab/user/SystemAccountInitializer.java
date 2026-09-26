package com.smartcollab.user;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 탈퇴한 사용자의 팀 데이터를 넘겨받을 시스템 계정을 준비합니다.
 * <p>v1 은 이 계정의 비밀번호를 소스 코드에 평문으로 적어 두어, 공개 저장소를 본 누구나 로그인할 수 있었습니다.
 * v2 는 매번 무작위 비밀번호를 해시해 저장하고, 역할(SYSTEM)로 로그인 자체를 막습니다.</p>
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class SystemAccountInitializer implements ApplicationRunner {

    public static final String USERNAME = "deleted_user";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.findFirstByRole(Role.SYSTEM).isPresent()) {
            return;
        }
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String unusable = passwordEncoder.encode(Base64.getEncoder().encodeToString(random));
        users.save(new User(USERNAME, unusable, "탈퇴한 사용자", null, Role.SYSTEM));
        log.info("System account '{}' created", USERNAME);
    }
}
