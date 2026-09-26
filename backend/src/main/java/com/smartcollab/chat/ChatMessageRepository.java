package com.smartcollab.chat;

import com.smartcollab.user.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /** 커서 기반 페이지: before 보다 작은 id 중 최신순으로 size 개. (team_id, message_id) 인덱스를 탑니다. */
    @Query("""
            select m from ChatMessage m join fetch m.sender
            where m.team.id = :teamId and m.id < :before
            order by m.id desc
            """)
    List<ChatMessage> findPage(@Param("teamId") Long teamId, @Param("before") long before, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from ChatMessage m where m.team.id = :teamId")
    int deleteByTeam(@Param("teamId") Long teamId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ChatMessage m set m.sender = :to where m.sender.id = :fromUserId")
    int transferSender(@Param("fromUserId") Long fromUserId, @Param("to") User to);
}
