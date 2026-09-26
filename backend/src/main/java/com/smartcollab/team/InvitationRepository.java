package com.smartcollab.team;

import org.springframework.data.jpa.repository.JpaRepository;
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

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Invitation i where i.team.id = :teamId")
    int deleteByTeam(@Param("teamId") Long teamId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Invitation i where i.inviter.id = :userId or i.invitee.id = :userId")
    int deleteByUser(@Param("userId") Long userId);
}
