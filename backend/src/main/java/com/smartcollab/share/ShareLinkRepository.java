package com.smartcollab.share;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ShareLinkRepository extends JpaRepository<ShareLink, Long> {

    @Query("select l from ShareLink l join fetch l.file f join fetch f.activeVersion where l.token = :token")
    Optional<ShareLink> findByToken(@Param("token") String token);

    @Query("select l from ShareLink l where l.file.id = :fileId order by l.createdAt desc")
    List<ShareLink> findByFile(@Param("fileId") Long fileId);

    /**
     * 다운로드 횟수를 원자적으로 1 늘립니다. 한도를 넘었으면 0 을 반환합니다.
     * v1 은 "읽고-비교하고-저장"을 나눠 수행해 동시에 요청하면 한도를 넘겨 내려받을 수 있었습니다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ShareLink l set l.downloadCount = l.downloadCount + 1
            where l.id = :id and (l.downloadLimit is null or l.downloadCount < l.downloadLimit)
            """)
    int tryConsumeDownload(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from ShareLink l where l.file.id in :fileIds")
    int deleteByFileIds(@Param("fileIds") Collection<Long> fileIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from ShareLink l where l.owner.id = :userId")
    int deleteByOwner(@Param("userId") Long userId);
}
