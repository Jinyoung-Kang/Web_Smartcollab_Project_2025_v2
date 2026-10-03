package com.smartcollab.file;

import com.smartcollab.folder.Folder;
import com.smartcollab.global.config.AppProperties;
import com.smartcollab.global.error.ApiException;
import com.smartcollab.global.error.ErrorCode;
import com.smartcollab.team.Team;
import com.smartcollab.team.TeamRepository;
import com.smartcollab.user.DemoAccounts;
import com.smartcollab.user.User;
import com.smartcollab.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * 저장 공간 한도 [SEC-05].
 * <p>누구나 가입해 파일을 올릴 수 있으므로, 개인 저장소와 팀 저장소마다 한도를 둡니다. 한도는 사용자가 보는 파일 크기가 아니라
 * <b>실제 저장량</b>(옛 버전·휴지통 포함)으로 셉니다 — 그렇지 않으면 텍스트를 반복 저장해 버전을 한없이 쌓을 수 있습니다.
 * 체험 계정의 개인 저장소와 체험 계정이 팀장인 팀에는 더 작은 체험 한도를 적용합니다 [SEC-06].</p>
 * <p>저장 직전에는 저장소 주인(사용자·팀) 행을 잠가 같은 저장소의 동시 저장을 줄 세운 뒤 다시 확인하므로, 동시에 올려도 한도를 넘지 않습니다.</p>
 */
@Service
@RequiredArgsConstructor
public class StorageQuota {

    private final FileVersionRepository versions;
    private final UserRepository users;
    private final TeamRepository teams;
    private final DemoAccounts demoAccounts;
    private final AppProperties props;

    /** 저장소: 개인(소유자) 또는 팀 */
    public record Scope(Long ownerId, Long teamId) {
        public static Scope of(Folder folder) {
            return folder.getTeam() == null ? personal(folder.getOwner().getId()) : team(folder.teamId());
        }

        public static Scope personal(Long userId) {
            return new Scope(userId, null);
        }

        public static Scope team(Long teamId) {
            return new Scope(null, teamId);
        }

        boolean isTeam() {
            return teamId != null;
        }
    }

    public long usedBytes(Scope scope) {
        return scope.isTeam() ? versions.sumTeamBytes(scope.teamId()) : versions.sumPersonalBytes(scope.ownerId());
    }

    public long limitBytes(Scope scope) {
        User accountable = scope.isTeam()
                ? teams.findWithOwner(scope.teamId()).map(Team::getOwner).orElse(null)
                : users.findById(scope.ownerId()).orElse(null);
        if (demoAccounts.isDemo(accountable)) {
            return props.demo().quota().toBytes();
        }
        return (scope.isTeam() ? props.quota().team() : props.quota().personal()).toBytes();
    }

    /** 저장소에 쓰기 전 빠른 확인 (잠금 없음). 공간이 없으면 저장소에 쓰지도 않고 거절하기 위함입니다. */
    public void checkRoom(Scope scope, long additionalBytes) {
        requireRoom(scope, additionalBytes);
    }

    /**
     * 저장 트랜잭션 안에서: 저장소 주인 행을 잠가 동시 저장을 줄 세운 뒤, 최신 커밋 데이터로 다시 확인합니다.
     * <p><b>READ COMMITTED 트랜잭션에서 불러야 합니다</b>(TransactionRunner.writeReadCommitted). 잠근 뒤의 일반 읽기가 최신 커밋을
     * 보기 때문입니다. 이전에는 REPEATABLE READ 에서 최신 값을 보려고 잠금 읽기(SUM … FOR SHARE)를 썼는데, 이 읽기가 인덱스 간격까지
     * 잠가 <b>서로 다른 사용자</b>의 업로드·저장이 서로의 간격 잠금을 기다리다 교착(MySQL 1213)으로 500 이 났습니다 [QA-06].</p>
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockAndCheckRoom(Scope scope, long additionalBytes) {
        if (scope.isTeam()) {
            teams.lockById(scope.teamId());
        } else {
            users.lockById(scope.ownerId());
        }
        requireRoom(scope, usedBytes(scope), additionalBytes);
    }

    private void requireRoom(Scope scope, long additionalBytes) {
        requireRoom(scope, usedBytes(scope), additionalBytes);
    }

    private void requireRoom(Scope scope, long used, long additionalBytes) {
        long limit = limitBytes(scope);
        if (used + additionalBytes > limit) {
            throw new ApiException(ErrorCode.QUOTA_EXCEEDED, (scope.isTeam() ? "팀 저장 공간" : "저장 공간")
                    + "이 부족합니다 (" + human(used) + " / " + human(limit) + " 사용 중, 옛 버전·휴지통 포함). "
                    + "휴지통을 비우거나 파일을 정리하세요.");
        }
    }

    static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return (value >= 100 || value == Math.floor(value) ? String.format(Locale.ROOT, "%.0f", value) : String.format(Locale.ROOT, "%.1f", value))
                + " " + units[unit];
    }
}
