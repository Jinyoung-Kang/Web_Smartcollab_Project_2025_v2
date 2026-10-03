package com.smartcollab.file;

import com.smartcollab.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface FileVersionRepository extends JpaRepository<FileVersion, Long> {

    @Query("select v from FileVersion v join fetch v.editor where v.file.id = :fileId order by v.id desc")
    List<FileVersion> findHistory(@Param("fileId") Long fileId);

    /** 개인 저장소의 실제 저장량 (옛 버전·휴지통 포함) */
    @Query("select coalesce(sum(v.size), 0) from FileVersion v where v.file.folder.team is null and v.file.folder.owner.id = :ownerId")
    long sumPersonalBytes(@Param("ownerId") Long ownerId);

    /** 팀 저장소의 실제 저장량 (옛 버전·휴지통 포함) */
    @Query("select coalesce(sum(v.size), 0) from FileVersion v where v.file.folder.team.id = :teamId")
    long sumTeamBytes(@Param("teamId") Long teamId);

    /** 지울 파일들의 버전 크기 합을 저장 공간(개인 소유자 또는 팀)별로 [IMP-01] */
    @Query(value = """
            SELECT CASE WHEN fo.team_id IS NULL THEN fo.owner_id END AS ownerId, fo.team_id AS teamId,
                   CAST(SUM(v.size) AS SIGNED) AS bytes
            FROM file_versions v JOIN files f ON f.file_id = v.file_id JOIN folders fo ON fo.folder_id = f.folder_id
            WHERE v.file_id IN (:fileIds)
            GROUP BY CASE WHEN fo.team_id IS NULL THEN fo.owner_id END, fo.team_id
            """, nativeQuery = true)
    List<ScopeBytes> sumByScope(@Param("fileIds") Collection<Long> fileIds);

    interface ScopeBytes {
        Long getOwnerId();

        Long getTeamId();

        Long getBytes();
    }

    /** 사용량 집계가 실제 합계와 다른 사용자(개인 저장 공간) — 매일 정리 작업용 [IMP-01] */
    @Query(value = """
            SELECT u.user_id FROM users u
            LEFT JOIN (SELECT fo.owner_id AS owner_id, SUM(v.size) AS bytes
                       FROM file_versions v JOIN files f ON f.file_id = v.file_id JOIN folders fo ON fo.folder_id = f.folder_id
                       WHERE fo.team_id IS NULL GROUP BY fo.owner_id) s ON s.owner_id = u.user_id
            WHERE u.stored_bytes <> COALESCE(s.bytes, 0)
            """, nativeQuery = true)
    List<Long> findUsersWithDriftedUsage();

    @Query(value = """
            SELECT t.team_id FROM teams t
            LEFT JOIN (SELECT fo.team_id AS team_id, SUM(v.size) AS bytes
                       FROM file_versions v JOIN files f ON f.file_id = v.file_id JOIN folders fo ON fo.folder_id = f.folder_id
                       WHERE fo.team_id IS NOT NULL GROUP BY fo.team_id) s ON s.team_id = t.team_id
            WHERE t.stored_bytes <> COALESCE(s.bytes, 0)
            """, nativeQuery = true)
    List<Long> findTeamsWithDriftedUsage();

    /** 주어진 저장소 키 중 버전 행이 가리키는 것만(고아 파일 정리용, stored_path 유일 인덱스) [IMP-05] */
    @Query("select v.storedPath from FileVersion v where v.storedPath in :keys")
    List<String> findReferencedStoredPaths(@Param("keys") Collection<String> keys);

    @Query("select v.storedPath from FileVersion v where v.file.id in :fileIds")
    List<String> findStoredPaths(@Param("fileIds") Collection<Long> fileIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from FileVersion v where v.file.id in :fileIds")
    int deleteByFileIds(@Param("fileIds") Collection<Long> fileIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update FileVersion v set v.editor = :to where v.editor.id = :fromUserId")
    int transferEditor(@Param("fromUserId") Long fromUserId, @Param("to") User to);
}
