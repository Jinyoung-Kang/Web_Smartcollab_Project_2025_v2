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

    /**
     * 위와 같지만 잠금 읽기(FOR SHARE)라 트랜잭션 스냅샷이 아닌 최신 커밋 데이터를 셉니다.
     * REPEATABLE READ 에서는 잠금을 기다린 뒤에도 일반 읽기가 트랜잭션 초반의 스냅샷을 보므로, 한도 확인에는 이 쿼리를 씁니다.
     */
    @Query(value = """
            SELECT COALESCE(SUM(v.size), 0) FROM file_versions v
            JOIN files f ON f.file_id = v.file_id JOIN folders fo ON fo.folder_id = f.folder_id
            WHERE fo.team_id IS NULL AND fo.owner_id = :ownerId FOR SHARE
            """, nativeQuery = true)
    long sumPersonalBytesCurrent(@Param("ownerId") Long ownerId);

    @Query(value = """
            SELECT COALESCE(SUM(v.size), 0) FROM file_versions v
            JOIN files f ON f.file_id = v.file_id JOIN folders fo ON fo.folder_id = f.folder_id
            WHERE fo.team_id = :teamId FOR SHARE
            """, nativeQuery = true)
    long sumTeamBytesCurrent(@Param("teamId") Long teamId);

    @Query("select v.storedPath from FileVersion v where v.file.id in :fileIds")
    List<String> findStoredPaths(@Param("fileIds") Collection<Long> fileIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from FileVersion v where v.file.id in :fileIds")
    int deleteByFileIds(@Param("fileIds") Collection<Long> fileIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update FileVersion v set v.editor = :to where v.editor.id = :fromUserId")
    int transferEditor(@Param("fromUserId") Long fromUserId, @Param("to") User to);
}
