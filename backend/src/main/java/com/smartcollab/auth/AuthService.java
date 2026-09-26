package com.smartcollab.auth;

import com.smartcollab.folder.Folder;
import com.smartcollab.folder.FolderRepository;
import com.smartcollab.global.config.AppProperties;
import com.smartcollab.global.error.ApiException;
import com.smartcollab.global.error.ErrorCode;
import com.smartcollab.global.security.JwtTokenService;
import com.smartcollab.global.security.SlidingWindowRateLimiter;
import com.smartcollab.user.Role;
import com.smartcollab.user.User;
import com.smartcollab.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository users;
    private final FolderRepository folders;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokens;
    private final SlidingWindowRateLimiter rateLimiter;
    private final AppProperties props;

    /** 존재하지 않는 아이디로 로그인할 때도 BCrypt 비교를 수행해 응답 시간으로 계정 존재 여부를 추측하지 못하게 합니다. */
    private volatile String dummyHash;

    @Transactional
    public User signUp(AuthDtos.SignUpRequest req) {
        if (!req.password().equals(req.passwordConfirm())) {
            throw ApiException.badRequest("비밀번호와 비밀번호 확인이 일치하지 않습니다.");
        }
        if (users.existsByUsername(req.username())) {
            throw ApiException.conflict("이미 사용 중인 아이디입니다.");
        }
        // v1 은 빈 이메일("")을 그대로 저장해, 이메일 없이 가입한 두 번째 사용자부터 UNIQUE 위반(500)이 났습니다.
        String email = StringUtils.hasText(req.email()) ? req.email().strip().toLowerCase(Locale.ROOT) : null;
        if (email != null && users.existsByEmail(email)) {
            throw ApiException.conflict("이미 사용 중인 이메일입니다.");
        }
        User user = users.save(new User(req.username(), passwordEncoder.encode(req.password()), req.name().strip(),
                email, Role.USER));
        folders.save(Folder.personalRoot(user));
        return user;
    }

    @Transactional(readOnly = true)
    public User authenticate(String username, String password, String clientIp) {
        if (!rateLimiter.tryAcquire("login:" + clientIp, props.rateLimit().loginPerMinute(), Duration.ofMinutes(1))) {
            throw new ApiException(ErrorCode.RATE_LIMITED, "로그인 시도가 너무 많습니다. 1분 뒤 다시 시도하세요.");
        }
        User user = users.findByUsername(username).orElse(null);
        String hash = user == null ? dummyHash() : user.getPassword();
        boolean matches = passwordEncoder.matches(password, hash);
        if (user == null || !matches || user.isSystem()) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        return user;
    }

    private String dummyHash() {
        if (dummyHash == null) {
            dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
        }
        return dummyHash;
    }

    public String issueToken(User user) {
        return tokens.issue(user.getId(), user.getUsername());
    }

    public Duration tokenTtl() {
        return tokens.ttl();
    }

    @Transactional
    public AuthDtos.MeResponse me(Long userId) {
        User user = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        // 루트 폴더가 없는 계정(과거 데이터)은 이 시점에 만들어 줍니다.
        Folder root = folders.findPersonalRoot(userId).orElseGet(() -> folders.save(Folder.personalRoot(user)));
        return new AuthDtos.MeResponse(user.getId(), user.getUsername(), user.getName(), user.getEmail(), root.getId(),
                user.getCreatedAt());
    }
}
