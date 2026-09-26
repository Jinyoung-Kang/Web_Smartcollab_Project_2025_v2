package com.smartcollab.file;

import com.smartcollab.user.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface FileRepository extends JpaRepository<FileEntity, Long> {

    /** 권한 판단에 필요한 폴더까지 한 번에 읽습니다. */
    @Query("select f from FileEntity f join fetch f.folder where f.id = :id")
    Optional<FileEntity> findWithFolder(@Param("id") Long id);

    @Query("select f from FileEntity f join fetch f.owner where f.folder.id = :folderId and f.deleted = false")
    List<FileEntity> findActiveInFolder(@Param("folderId") Long folderId);

    @Query("""
            select f from FileEntity f join fetch f.owner
            where f.folder.id in :folderIds and f.deleted = false and lower(f.name) like :pattern escape '!'
            order by f.name asc
            """)
    List<FileEntity> search(@Param("folderIds") Collection<Long> folderIds, @Param("pattern") String pattern,
                            Pageable pageable);

    @Query("""
            select f from FileEntity f join fetch f.folder fo left join fetch f.deletedBy
            where f.deleted = true and f.owner.id = :userId and fo.team is null
            order by f.deletedAt desc
            """)
    List<FileEntity> findPersonalTrash(@Param("userId") Long userId);

    @Query("""
            select f from FileEntity f join fetch f.folder fo left join fetch f.deletedBy
            where f.deleted = true and fo.team.id = :teamId
            order by f.deletedAt desc
            """)
    List<FileEntity> findTeamTrash(@Param("teamId") Long teamId);

    @Query("select f.id from FileEntity f where f.deleted = true and f.deletedAt < :cutoff")
    List<Long> findTrashedBefore(@Param("cutoff") Instant cutoff);

    /** 휴지통에 있는 파일까지 포함합니다 (폴더 영구 삭제 시 함께 지워야 하므로). */
    @Query("select f.id from FileEntity f where f.folder.id in :folderIds")
    List<Long> findIdsInFolders(@Param("folderIds") Collection<Long> folderIds);

    @Query("select f from FileEntity f join fetch f.activeVersion where f.folder.id in :folderIds and f.deleted = false")
    List<FileEntity> findActiveWithVersionInFolders(@Param("folderIds") Collection<Long> folderIds);

    @Query("""
            select new com.smartcollab.file.StorageUsage(count(f), coalesce(sum(f.size), 0))
            from FileEntity f where f.folder.id in :folderIds and f.deleted = false
            """)
    StorageUsage usageOf(@Param("folderIds") Collection<Long> folderIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update FileEntity f set f.activeVersion = null where f.id in :ids")
    int detachActiveVersions(@Param("ids") Collection<Long> ids);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from FileEntity f where f.id in :ids")
    int deleteAllByIds(@Param("ids") Collection<Long> ids);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update FileEntity f set f.owner = :to
            where f.owner.id = :fromUserId
              and f.folder.id in (select fo.id from Folder fo where fo.team is not null)
            """)
    int transferTeamFiles(@Param("fromUserId") Long fromUserId, @Param("to") User to);
}
