package com.smartcollab.folder;

import com.smartcollab.team.Team;
import com.smartcollab.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 개인·팀 스토리지의 최상위 폴더를 만들고 찾습니다 [A-04]. 이전에는 가입(auth)·팀 만들기(team)가 폴더 리포지토리로
 * 직접 만들었습니다. 다른 모듈은 이 컴포넌트를 거칩니다.
 */
@Component
@RequiredArgsConstructor
public class RootFolders {

    private final FolderRepository folders;

    public Folder createPersonal(User user) {
        return folders.save(Folder.personalRoot(user));
    }

    /** 개인 최상위 폴더. 없는 계정(과거 데이터)이면 이 자리에서 만듭니다. */
    public Folder personalOf(User user) {
        return folders.findPersonalRoot(user.getId()).orElseGet(() -> createPersonal(user));
    }

    public Folder createTeam(Team team, User creator) {
        return folders.save(Folder.teamRoot(team, creator));
    }

    /** 팀 최상위 폴더 ID (없으면 null) */
    public Long teamRootId(Long teamId) {
        return folders.findTeamRoot(teamId).map(Folder::getId).orElse(null);
    }

    /** 여러 팀의 최상위 폴더 ID 를 쿼리 한 번으로 (팀 목록 N+1 방지) */
    public Map<Long, Long> teamRootIds(Collection<Long> teamIds) {
        return folders.findTeamRootIds(teamIds).stream()
                .collect(Collectors.toMap(TeamRoot::teamId, TeamRoot::folderId, Math::min));
    }
}
