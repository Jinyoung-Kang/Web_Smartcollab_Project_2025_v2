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

    @Query("select v.storedPath from FileVersion v where v.file.id in :fileIds")
    List<String> findStoredPaths(@Param("fileIds") Collection<Long> fileIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from FileVersion v where v.file.id in :fileIds")
    int deleteByFileIds(@Param("fileIds") Collection<Long> fileIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update FileVersion v set v.editor = :to where v.editor.id = :fromUserId")
    int transferEditor(@Param("fromUserId") Long fromUserId, @Param("to") User to);
}
