package com.smartcollab.folder;

import com.smartcollab.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface FolderRepository extends JpaRepository<Folder, Long> {

    @Query("select f from Folder f where f.owner.id = :ownerId and f.team is null and f.parent is null order by f.id")
    List<Folder> findPersonalRoots(@Param("ownerId") Long ownerId);

    default Optional<Folder> findPersonalRoot(Long ownerId) {
        return findPersonalRoots(ownerId).stream().findFirst();
    }

    @Query("select f from Folder f where f.team.id = :teamId and f.parent is null order by f.id")
    List<Folder> findTeamRoots(@Param("teamId") Long teamId);

    default Optional<Folder> findTeamRoot(Long teamId) {
        return findTeamRoots(teamId).stream().findFirst();
    }

    /** 여러 팀의 루트 폴더 ID 를 한 번에 조회 (팀 목록 N+1 방지) */
    @Query("select new com.smartcollab.folder.TeamRoot(f.team.id, f.id) from Folder f where f.team.id in :teamIds and f.parent is null")
    List<TeamRoot> findTeamRootIds(@Param("teamIds") Collection<Long> teamIds);

    /** 휴지통에 있는 하위 폴더는 빼고 */
    @Query("select f from Folder f join fetch f.owner where f.parent.id = :parentId and f.trashRootId is null")
    List<Folder> findChildren(@Param("parentId") Long parentId);

    /** 개인 스토리지 전체 폴더 (트리·검색·사용량용, 쿼리 1회). 휴지통에 있는 폴더 트리는 빼고 */
    @Query("""
            select new com.smartcollab.folder.FolderNode(f.id, f.name, p.id)
            from Folder f left join f.parent p
            where f.owner.id = :ownerId and f.team is null and f.trashRootId is null
            """)
    List<FolderNode> findPersonalNodes(@Param("ownerId") Long ownerId);

    /** 팀 스토리지 전체 폴더 (트리·검색·사용량용, 쿼리 1회). 휴지통에 있는 폴더 트리는 빼고 */
    @Query("""
            select new com.smartcollab.folder.FolderNode(f.id, f.name, p.id)
            from Folder f left join f.parent p
            where f.team.id = :teamId and f.trashRootId is null
            """)
    List<FolderNode> findTeamNodes(@Param("teamId") Long teamId);

    /** 폴더가 속한 저장 공간 — 엔티티를 영속성 컨텍스트에 올리지 않습니다(잠그기 전에 쓰기 위함) [S-06] */
    @Query("select new com.smartcollab.folder.FolderScope(o.id, t.id) from Folder f join f.owner o left join f.team t where f.id = :id")
    java.util.Optional<FolderScope> findScope(@Param("id") Long id);

    /**
     * 루트부터 자신까지의 경로 (재귀 CTE, 쿼리 1회). v1 은 부모를 하나씩 지연 로딩해 깊이만큼 쿼리가 늘었습니다.
     */
    @Query(value = """
            WITH RECURSIVE ancestors (folder_id, name, parent_folder_id, depth) AS (
                SELECT folder_id, name, parent_folder_id, 0 FROM folders WHERE folder_id = :folderId
                UNION ALL
                SELECT f.folder_id, f.name, f.parent_folder_id, a.depth + 1
                FROM folders f JOIN ancestors a ON f.folder_id = a.parent_folder_id
            )
            SELECT folder_id AS id, name AS name, parent_folder_id AS parentId FROM ancestors ORDER BY depth DESC
            """, nativeQuery = true)
    List<PathRow> findPath(@Param("folderId") Long folderId);

    /**
     * 자신을 포함한 모든 하위 폴더와 깊이 (재귀 CTE, 쿼리 1회). 삭제·복사·이동 순환 검사에 사용합니다.
     */
    @Query(value = """
            WITH RECURSIVE subtree (folder_id, depth) AS (
                SELECT folder_id, 0 FROM folders WHERE folder_id = :folderId
                UNION ALL
                SELECT f.folder_id, s.depth + 1
                FROM folders f JOIN subtree s ON f.parent_folder_id = s.folder_id
            )
            SELECT folder_id AS id, depth AS depth FROM subtree
            """, nativeQuery = true)
    List<SubtreeRow> findSubtree(@Param("folderId") Long folderId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Folder f where f.id in :ids")
    int deleteAllByIds(@Param("ids") Collection<Long> ids);

    /**
     * 폴더 트리를 휴지통에 넣습니다 [UX-06]. 이미 휴지통에 있던 하위 트리(따로 지운 폴더)는 그대로 두어,
     * 휴지통 목록에 따로 남고 따로 복원할 수 있게 합니다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Folder f set f.trashRootId = :rootId where f.id in :ids and f.trashRootId is null")
    int markInTrash(@Param("ids") Collection<Long> ids, @Param("rootId") Long rootId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Folder f set f.deletedAt = :at, f.deletedBy = :by where f.id = :rootId")
    int stampTrashRoot(@Param("rootId") Long rootId, @Param("at") Instant at, @Param("by") User by);

    /**
     * 영구 삭제 전에 휴지통 표시를 지웁니다. 휴지통의 맨 위 폴더는 자기 자신을 가리키는데(자기 참조 FK),
     * InnoDB 는 자기 자신을 참조하는 행을 지우지 못하게 막기 때문입니다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Folder f set f.trashRootId = null where f.id in :ids and f.trashRootId is not null")
    int clearTrashMarks(@Param("ids") Collection<Long> ids);

    /** 이 폴더를 맨 위로 휴지통에 들어간 트리를 꺼냅니다 (따로 지운 하위 트리는 휴지통에 남음) */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Folder f set f.trashRootId = null, f.deletedAt = null, f.deletedBy = null where f.trashRootId = :rootId")
    int restoreFromTrash(@Param("rootId") Long rootId);

    @Query("""
            select f from Folder f left join fetch f.deletedBy
            where f.trashRootId = f.id and f.team is null and f.owner.id = :userId
            """)
    List<Folder> findPersonalTrashRoots(@Param("userId") Long userId);

    @Query("select f from Folder f left join fetch f.deletedBy where f.trashRootId = f.id and f.team.id = :teamId")
    List<Folder> findTeamTrashRoots(@Param("teamId") Long teamId);

    @Query("select f.id from Folder f where f.trashRootId = f.id and f.deletedAt < :cutoff")
    List<Long> findTrashRootsBefore(@Param("cutoff") Instant cutoff);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Folder f set f.owner = :to
            where f.owner.id = :fromUserId and f.team is not null
            """)
    int transferTeamFolders(@Param("fromUserId") Long fromUserId, @Param("to") User to);

    interface PathRow {
        Long getId();

        String getName();

        Long getParentId();
    }

    interface SubtreeRow {
        Long getId();

        Integer getDepth();
    }
}
