package com.smartcollab.team;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface InvitationRepository extends JpaRepository<Invitation, Long> {

    boolean existsByTeamIdAndInviteeIdAndStatus(Long teamId, Long inviteeId, Invitation.Status status);

    @Query("""
            select i from Invitation i join fetch i.team join fetch i.inviter join fetch i.invitee
            where i.id = :id
            """)
    Optional<Invitation> findDetailed(@Param("id") Long id);

    /** 초대 행만 잠급니다(SELECT … FOR UPDATE). 같은 초대에 대한 수락·거절을 줄 세웁니다 [QA-05] */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invitation i where i.id = :id")
    Optional<Invitation> lockById(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Invitation i where i.team.id = :teamId")
    int deleteByTeam(@Param("teamId") Long teamId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Invitation i where i.inviter.id = :userId or i.invitee.id = :userId")
    int deleteByUser(@Param("userId") Long userId);
}
