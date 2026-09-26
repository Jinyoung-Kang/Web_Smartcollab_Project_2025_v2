package com.smartcollab.signature;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface SignatureRepository extends JpaRepository<Signature, Long> {

    @Query("select s from Signature s join fetch s.signer where s.file.id = :fileId order by s.signedAt asc")
    List<Signature> findByFile(@Param("fileId") Long fileId);

    boolean existsByFileVersionIdAndSignerId(Long versionId, Long signerId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Signature s set s.valid = false where s.file.id = :fileId and s.valid = true")
    int invalidateAll(@Param("fileId") Long fileId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Signature s where s.file.id in :fileIds")
    int deleteByFileIds(@Param("fileIds") Collection<Long> fileIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Signature s where s.signer.id = :userId")
    int deleteBySigner(@Param("userId") Long userId);
}
