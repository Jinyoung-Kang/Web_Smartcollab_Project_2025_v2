package com.smartcollab.user;

import com.smartcollab.global.config.AppProperties;
import com.smartcollab.global.error.ApiException;
import com.smartcollab.global.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 데모 모드(DEMO_ENABLED=true)의 체험 계정.
 * <p>체험 계정은 비밀번호가 공개되어 여러 방문자가 함께 쓰므로, 다른 방문자의 체험을 망가뜨리는 작업
 * (탈퇴·팀 삭제·팀장 위임·팀 나가기·체험 계정 내보내기)을 막고, 데이터는 매일 초기화합니다 [SEC-06].
 * 데모 모드가 아니면 같은 이름의 계정도 일반 계정으로 취급합니다.</p>
 */
@Component
public class DemoAccounts {

    public static final List<Account> ACCOUNTS = List.of(
            new Account("demo1", "김하늘", "팀장"),
            new Account("demo2", "이도윤", "팀원 (편집)"),
            new Account("demo3", "박서연", "팀원 (편집·삭제)"));

    private static final Set<String> USERNAMES = ACCOUNTS.stream().map(Account::username).collect(Collectors.toUnmodifiableSet());

    private final boolean enabled;

    public DemoAccounts(AppProperties props) {
        this.enabled = props.demo().enabled();
    }

    public boolean isDemo(User user) {
        // 아이디 비교는 DB 콜레이션(대소문자 구분 없음)과 같게 소문자로 합니다.
        return enabled && user != null && USERNAMES.contains(user.getUsername().toLowerCase(Locale.ROOT));
    }

    /** 체험 계정이면 거절합니다. reason 예: "체험 계정은 탈퇴할 수 없습니다." */
    public void forbidIfDemo(User user, String reason) {
        if (isDemo(user)) {
            throw new ApiException(ErrorCode.FORBIDDEN, reason + " 체험 데이터는 주기적으로 초기화됩니다.");
        }
    }

    public record Account(String username, String name, String role) {
    }
}
