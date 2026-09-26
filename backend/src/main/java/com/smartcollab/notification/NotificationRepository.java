package com.smartcollab.notification;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    @Query("""
            select n from Notification n left join fetch n.invitation left join fetch n.team
            where n.user.id = :userId order by n.createdAt desc, n.id desc
            """)
    List<Notification> findRecent(@Param("userId") Long userId, Pageable pageable);

    long countByUserIdAndReadFalse(Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Notification n set n.read = true where n.user.id = :userId and n.read = false")
    int markAllRead(@Param("userId") Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Notification n where n.user.id = :userId")
    int deleteByUser(@Param("userId") Long userId);
}
