package com.smartcollab.folder;

import com.smartcollab.team.Team;
import com.smartcollab.team.TeamRootFolders;
import com.smartcollab.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 개인·팀 스토리지의 최상위 폴더를 만들고 찾습니다 [A-04]. 이전에는 가입(auth)·팀 만들기(team)가 폴더 리포지토리로
 * 직접 만들었습니다. 다른 모듈은 이 컴포넌트를 거치고, 팀 모듈은 자신이 정한 {@link TeamRootFolders} 로만 씁니다 [L-7].
 */
@Component
@RequiredArgsConstructor
public class RootFolders implements TeamRootFolders {

    private final FolderRepository folders;

    public Folder createPersonal(User user) {
        return folders.save(Folder.personalRoot(user));
    }

    /** 개인 최상위 폴더. 없는 계정(과거 데이터)이면 이 자리에서 만듭니다. */
    public Folder personalOf(User user) {
        return folders.findPersonalRoot(user.getId()).orElseGet(() -> createPersonal(user));
    }

    @Override
    public Long createRoot(Team team, User creator) {
        return folders.save(Folder.teamRoot(team, creator)).getId();
    }

    @Override
    public Long teamRootId(Long teamId) {
        return folders.findTeamRoot(teamId).map(Folder::getId).orElse(null);
    }

    @Override
    public Map<Long, Long> teamRootIds(Collection<Long> teamIds) {
        return folders.findTeamRootIds(teamIds).stream()
                .collect(Collectors.toMap(TeamRoot::teamId, TeamRoot::folderId, Math::min));
    }
}
