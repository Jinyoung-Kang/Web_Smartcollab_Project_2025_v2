package com.smartcollab.user;

import com.smartcollab.event.DeletionEvents;
import com.smartcollab.global.error.ApiException;
import com.smartcollab.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 탈퇴.
 * <p>v1 은 비밀번호 재확인 없이 즉시 탈퇴했고, 다음 참조를 정리하지 않아 탈퇴가 FK 오류로 실패할 수 있었습니다:
 * 팀 파일의 버전 작성자(file_versions.editor_id), 나에게 걸린 공유 링크, 휴지통 파일이 남은 개인 폴더.</p>
 * <ul>
 *   <li>개인 스토리지: 폴더·파일(휴지통 포함)·버전·서명·공유 링크를 모두 영구 삭제</li>
 *   <li>팀 스토리지: 내가 만든 폴더·파일·버전·채팅은 팀 자료이므로 남기고, 작성자를 "탈퇴한 사용자"로 바꿈</li>
 *   <li>초대·알림·멤버십·내가 만든 공유 링크·서명 삭제</li>
 * </ul>
 * <p>각 데이터는 그 데이터를 가진 모듈이 {@link DeletionEvents.AccountDeleting} 을 듣고 같은 트랜잭션 안에서 정리합니다 [A-02].
 * 순서는 {@link DeletionEvents.Order} 에 있습니다. 이 서비스는 다른 모듈의 리포지토리를 직접 만지지 않습니다.</p>
 */
@Service
@RequiredArgsConstructor
public class AccountService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;
    private final DemoAccounts demoAccounts;

    @Transactional
    public void deleteAccount(Long userId, String password) {
        User user = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        demoAccounts.forbidIfDemo(user, "체험 계정은 탈퇴할 수 없습니다.");
        if (password == null || !passwordEncoder.matches(password, user.getPassword())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "비밀번호가 일치하지 않습니다.");
        }
        purgeAccount(userId);
    }

    /**
     * 계정과 개인 데이터를 지우고 팀 자료는 시스템 계정으로 넘깁니다. <b>비밀번호·체험 계정 여부는 확인하지 않으므로</b>
     * 호출하는 쪽이 확인해야 합니다 (회원 탈퇴 API, 데모 데이터 초기화). 팀장인 팀이 남아 있으면 409 로 거절됩니다.
     */
    @Transactional
    public void purgeAccount(Long userId) {
        User system = users.findFirstByRole(Role.SYSTEM)
                .orElseThrow(() -> new IllegalStateException("시스템 계정이 없습니다."));
        events.publishEvent(new DeletionEvents.AccountDeleting(userId, system.getId()));
        users.deleteById(userId);
    }
}
