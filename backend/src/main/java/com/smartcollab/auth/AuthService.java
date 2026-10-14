package com.smartcollab.auth;

import com.smartcollab.folder.Folder;
import com.smartcollab.folder.RootFolders;
import com.smartcollab.global.config.AppProperties;
import com.smartcollab.global.error.ApiException;
import com.smartcollab.global.error.ErrorCode;
import com.smartcollab.global.tx.TransactionRunner;
import com.smartcollab.global.security.JwtTokenService;
import com.smartcollab.global.security.SecurityEventLog;
import com.smartcollab.global.security.SlidingWindowRateLimiter;
import com.smartcollab.user.AdminAccounts;
import com.smartcollab.user.DemoAccounts;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository users;
    private final RootFolders rootFolders;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokens;
    private final SlidingWindowRateLimiter rateLimiter;
    private final AppProperties props;
    private final TransactionRunner tx;
    private final AdminAccounts adminAccounts;

    /** 존재하지 않는 아이디로 로그인할 때도 BCrypt 비교를 수행해 응답 시간으로 계정 존재 여부를 추측하지 못하게 합니다. */
    private static final Pattern USERNAME = Pattern.compile(AuthDtos.USERNAME_REGEX);

    private volatile String dummyHash;

    /**
     * 외부 가입을 한 번에 하나씩 — 계정 수를 센 뒤 넣는 사이에 다른 가입이 끼어들어 상한을 넘지 않게 합니다 [CAP-03]. 서버 안의 잠금이라
     * 인스턴스가 하나일 때만 정확합니다(App Service B1 1대 — 요청 제한기·동시 실행 상한과 같은 전제, ADR-0012). 시스템 계정 행을 DB 에서
     * 잠그는 방법은 탈퇴가 그 행을 참조하며 잠그는 순서와 반대라 같은 아이디의 재가입과 겹치면 교착될 수 있어 쓰지 않았습니다.
     */
    private final ReentrantLock signupLock = new ReentrantLock(true);

    /** 외부 요청의 가입. 한 IP 에서 계정을 대량으로 만드는 것을 막습니다 [SEC-05]. */
    public Account signUp(AuthDtos.SignUpRequest req, String clientIp) {
        if (!rateLimiter.tryAcquire("signup:" + clientIp, props.rateLimit().signupPerHour(), Duration.ofHours(1))) {
            SecurityEventLog.rateLimited("signup-ip", clientIp);
            throw new ApiException(ErrorCode.RATE_LIMITED, "가입 요청이 너무 많습니다. 잠시 후 다시 시도하세요.");
        }
        if (DemoAccounts.isReserved(req.username())) {   // [S-19] 체험 데이터 생성은 아래 signUp(req) 를 직접 씁니다
            throw ApiException.conflict("체험 계정용으로 예약된 아이디라 가입할 수 없습니다.");
        }
        requireAccountRoom();   // 꽉 찼으면 비밀번호 해시·잠금 없이 바로 거절
        return Account.of(create(req, true));
    }

    /**
     * 가입할 자리가 있는지 [CAP-03]. 체험 계정도 세고, 관리·시스템 계정은 세지 않습니다. 체험 모드면 체험 계정 자리를 늘 남겨 둡니다 —
     * 매일 초기화가 체험 계정을 지웠다가 다시 만드는 사이 일반 가입이 그 자리를 차지하면 체험 계정을 다시 만들지 못합니다.
     */
    private void requireAccountRoom() {
        int max = props.quota().accounts();
        long demo = users.countByRoleAndUsernameIn(Role.USER, DemoAccounts.usernames());
        long regular = users.countByRole(Role.USER) - demo;
        long demoSlots = props.demo().enabled() ? Math.max(demo, DemoAccounts.ACCOUNTS.size()) : demo;
        if (regular + demoSlots >= max) {
            throw ApiException.conflict("가입할 수 있는 계정 수(" + max + "개, 체험 계정 포함)를 모두 사용했습니다. 관리자에게 문의하세요.");
        }
    }

    /**
     * 가입 (요청 제한 없음 — 데모 데이터 생성 등 서버 내부용). 비밀번호 해시(bcrypt)는 트랜잭션 밖에서 만들어, 그동안 DB 커넥션을
     * 쥐지 않습니다 [M-2]. 인증 없이 부를 수 있는 경로라 동시 요청이 풀을 차지하면 다른 API 가 커넥션을 기다렸습니다.
     */
    public User signUp(AuthDtos.SignUpRequest req) {
        return create(req, false);
    }

    /** @param limited 외부 가입이면 계정 수 상한을 지킵니다(잠금 안에서 다시 세고 넣음) [CAP-03] */
    private User create(AuthDtos.SignUpRequest req, boolean limited) {
        if (!req.password().equals(req.passwordConfirm())) {
            throw ApiException.badRequest("비밀번호와 비밀번호 확인이 일치하지 않습니다.");
        }
        String hash = passwordEncoder.encode(req.password());
        // v1 은 빈 이메일("")을 그대로 저장해, 이메일 없이 가입한 두 번째 사용자부터 UNIQUE 위반(500)이 났습니다.
        String email = StringUtils.hasText(req.email()) ? req.email().strip().toLowerCase(Locale.ROOT) : null;
        if (!limited) {
            return insert(req, hash, email, false);
        }
        try {
            if (!signupLock.tryLock(10, TimeUnit.SECONDS)) {
                throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        try {
            return insert(req, hash, email, true);   // 커밋까지 잠금을 쥠
        } finally {
            signupLock.unlock();
        }
    }

    private User insert(AuthDtos.SignUpRequest req, String hash, String email, boolean limited) {
        return tx.writeReadCommitted(() -> {
            if (limited) {
                requireAccountRoom();
            }
            if (users.existsByUsername(req.username())) {
                throw ApiException.conflict("이미 사용 중인 아이디입니다.");
            }
            if (email != null && users.existsByEmail(email)) {
                throw ApiException.conflict("이미 사용 중인 이메일입니다.");
            }
            User user = users.save(new User(req.username(), hash, req.name().strip(), email, Role.USER));
            rootFolders.createPersonal(user);
            return user;
        });
    }

    /**
     * 로그인. 시도 횟수를 IP 단위(분당)와 계정 단위(10분)로 함께 제한합니다.
     * 계정 단위 제한은 여러 IP 에서 한 계정의 비밀번호를 맞히려는 시도를 막으며, 성공하면 초기화됩니다.
     * 제한에 걸리면 비밀번호가 맞아도 거절합니다(그렇지 않으면 응답 차이로 비밀번호를 계속 맞혀 볼 수 있음).
     * 트랜잭션 없이 사용자 조회 한 번만 DB 를 쓰고, bcrypt 비교 동안 커넥션을 쥐지 않습니다 [M-2].
     */
    public Account authenticate(String username, String password, String clientIp) {
        if (!rateLimiter.tryAcquire("login:" + clientIp, props.rateLimit().loginPerMinute(), Duration.ofMinutes(1))) {
            SecurityEventLog.rateLimited("login-ip", clientIp);
            throw new ApiException(ErrorCode.RATE_LIMITED, "로그인 시도가 너무 많습니다. 1분 뒤 다시 시도하세요.");
        }
        // 가입할 수 없는 형식의 아이디는 사용자 조회·계정 단위 시도 기록 없이 실패시킵니다. DB 콜레이션이 악센트를 무시해
        // "dèmo1" 같은 변형이 같은 계정에 맞으면서 시도 제한 키는 달라 계정 단위 제한을 우회할 수 있었습니다 [S-03].
        if (!USERNAME.matcher(username).matches()) {
            passwordEncoder.matches(password, dummyHash());   // 존재하는 아이디와 응답 시간을 맞춤
            SecurityEventLog.loginFailed(clientIp);
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        // 아이디 비교는 DB 콜레이션(대소문자 구분 없음)과 같게 소문자로 묶습니다.
        String accountKey = "login-account:" + username.strip().toLowerCase(Locale.ROOT);
        if (!rateLimiter.tryAcquire(accountKey, props.rateLimit().loginPerAccountPer10Minutes(), Duration.ofMinutes(10))) {
            SecurityEventLog.rateLimited("login-account", clientIp);
            throw new ApiException(ErrorCode.RATE_LIMITED, "이 계정의 로그인 시도가 너무 많습니다. 10분 뒤 다시 시도하세요.");
        }
        User user = users.findByUsername(username).orElse(null);
        String hash = user == null ? dummyHash() : user.getPassword();
        boolean matches = passwordEncoder.matches(password, hash);
        if (user == null || !matches || !canLogIn(user)) {
            SecurityEventLog.loginFailed(clientIp);
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        rateLimiter.reset(accountKey);
        SecurityEventLog.loginSucceeded(user.getId(), clientIp);
        return Account.of(user);
    }

    /** 시스템 계정은 로그인할 수 없고, 관리자 계정은 지금 설정된 관리자 아이디일 때만 됩니다 (ADR-0015) */
    private boolean canLogIn(User user) {
        return user.getRole() == Role.USER || adminAccounts.isActiveAdmin(user);
    }

    private String dummyHash() {
        if (dummyHash == null) {
            dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
        }
        return dummyHash;
    }

    public String issueToken(Account account) {
        return tokens.issue(account.id(), account.username(), account.role().name());
    }

    /** 가입·로그인한 계정. 컨트롤러에는 엔티티 대신 이것을 돌려줍니다 [A-05]. */
    public record Account(Long id, String username, Role role) {
        static Account of(User user) {
            return new Account(user.getId(), user.getUsername(), user.getRole());
        }
    }

    public Duration tokenTtl() {
        return tokens.ttl();
    }

    @Transactional
    public AuthDtos.MeResponse me(Long userId) {
        User user = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        if (user.isAdmin()) {   // 관리 전용 계정에는 드라이브가 없습니다 (ADR-0015)
            return new AuthDtos.MeResponse(user.getId(), user.getUsername(), user.getName(), user.getEmail(), null,
                    user.getCreatedAt(), user.getRole().name());
        }
        // 루트 폴더가 없는 계정(과거 데이터)은 이 시점에 만들어 줍니다.
        Folder root = rootFolders.personalOf(user);
        return new AuthDtos.MeResponse(user.getId(), user.getUsername(), user.getName(), user.getEmail(), root.getId(),
                user.getCreatedAt(), user.getRole().name());
    }

    /** 로그인 여부와 내 정보 [IMP-10]. 토큰은 유효한데 계정이 없으면 익명입니다. */
    @Transactional
    public AuthDtos.SessionResponse session(Long userId) {
        return users.existsById(userId) ? new AuthDtos.SessionResponse(true, me(userId)) : AuthDtos.SessionResponse.ANONYMOUS;
    }
}
