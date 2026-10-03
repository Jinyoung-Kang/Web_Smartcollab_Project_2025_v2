package com.smartcollab.folder;

import com.smartcollab.global.config.AppProperties;
import com.smartcollab.global.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * 폴더 깊이 상한 [S-05]. 최상위 폴더가 0단계이고, 생성·이동·복사 뒤 가장 깊은 폴더가 상한을 넘지 않게 합니다.
 * 상한이 없으면 MySQL 재귀 쿼리 한도(cte_max_recursion_depth, 기본 1000)를 넘는 사슬을 만들 수 있었고, 그러면 그 경로의 조회·이동과
 * 팀 삭제·회원 탈퇴(하위 트리를 재귀 쿼리로 찾음)가 영구히 실패했습니다.
 */
@Component
@RequiredArgsConstructor
public class FolderDepthPolicy {

    private final FolderRepository folders;
    private final AppProperties props;

    /** parent 아래에, 자기 아래로 height 단계가 더 있는 폴더를 둘 수 있는지 확인합니다 (새 폴더 하나면 height = 0). */
    public void requireRoomUnder(Folder parent, int height) {
        int deepest = folders.findPath(parent.getId()).size() + height;   // 경로 = 최상위..parent (parent 의 깊이 + 1)
        int max = props.files().maxFolderDepth();
        if (deepest > max) {
            throw ApiException.badRequest("폴더는 최상위 아래 " + max + "단계까지만 만들 수 있습니다.");
        }
    }

    /** 하위 트리 행에서 자신 아래로 몇 단계가 있는지 (자신만 있으면 0) */
    public static int heightOf(Collection<FolderRepository.SubtreeRow> subtree) {
        return subtree.stream().mapToInt(FolderRepository.SubtreeRow::getDepth).max().orElse(0);
    }
}
