package com.smartcollab.user;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 관리 화면이 읽는 일반 계정 정보 (ADR-0015). 시스템 계정("탈퇴한 사용자")과 관리자 계정은 목록·조회에서 빠집니다.
 * <p>다른 모듈이 사용자 리포지토리를 직접 쓰지 않도록 여기서 읽어 줍니다 (ADR-0010).</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccountDirectory {

    private final UserRepository users;

    /** 일반 계정을 가입일 최신순으로 */
    public Page<User> members(Pageable pageable) {
        return users.findByRoleOrderByCreatedAtDescIdDesc(Role.USER, pageable);
    }

    /** 일반 계정만 — 시스템·관리자 계정이나 없는 ID 는 비어 있음 */
    public Optional<User> findMember(Long userId) {
        return users.findById(userId).filter(u -> u.getRole() == Role.USER);
    }

    /** 사용자 ID → 개인 저장 사용량(옛 버전·휴지통 포함, 한도에 세는 값) [IMP-01] */
    public Map<Long, Long> storedBytes(Collection<Long> userIds) {
        Map<Long, Long> result = new HashMap<>();
        if (userIds.isEmpty()) return result;
        for (Object[] row : users.storedBytesOf(userIds)) {
            result.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return result;
    }
}
