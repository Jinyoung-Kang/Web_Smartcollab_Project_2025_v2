package com.smartcollab.team;

import com.smartcollab.user.User;

import java.util.Collection;
import java.util.Map;

/**
 * 팀 스토리지의 최상위 폴더 — 팀 모듈이 필요한 만큼만 정한 창구입니다(구현은 폴더 모듈의 RootFolders) [L-7].
 * 팀이 폴더 모듈을 직접 쓰면 폴더 → 팀(엔티티)과 팀 → 폴더가 서로 의존했습니다(ADR-0010).
 */
public interface TeamRootFolders {

    /** 새 팀의 최상위 폴더를 만들고 그 ID 를 돌려줍니다 */
    Long createRoot(Team team, User creator);

    /** 팀 최상위 폴더 ID (없으면 null) */
    Long teamRootId(Long teamId);

    /** 여러 팀의 최상위 폴더 ID 를 쿼리 한 번으로 (팀 목록 N+1 방지) */
    Map<Long, Long> teamRootIds(Collection<Long> teamIds);
}
