package com.smartcollab.team;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TeamMemberRepository extends JpaRepository<TeamMember, Long> {

    Optional<TeamMember> findByTeamIdAndUserId(Long teamId, Long userId);

    boolean existsByTeamIdAndUserId(Long teamId, Long userId);

    /** 멤버의 사용자 ID 만 읽습니다(엔티티를 영속성 컨텍스트에 올리지 않아, 잠근 뒤 다시 읽을 때 최신 행을 봅니다) [QA-01] */
    @Query("select m.user.id from TeamMember m where m.id = :memberId and m.team.id = :teamId")
    Optional<Long> findUserId(@Param("teamId") Long teamId, @Param("memberId") Long memberId);

    @Query("""
            select m from TeamMember m join fetch m.user
            where m.team.id = :teamId
            order by m.teamLeader desc, m.joinedAt asc
            """)
    List<TeamMember> findMembers(@Param("teamId") Long teamId);

    @Query("""
            select m from TeamMember m join fetch m.team t join fetch t.owner
            where m.user.id = :userId
            order by t.name asc
            """)
    List<TeamMember> findMembershipsOf(@Param("userId") Long userId);

    @Query("""
            select new com.smartcollab.team.TeamSize(m.team.id, count(m))
            from TeamMember m where m.team.id in :teamIds group by m.team.id
            """)
    List<TeamSize> countMembers(@Param("teamIds") Collection<Long> teamIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from TeamMember m where m.team.id = :teamId")
    int deleteByTeam(@Param("teamId") Long teamId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from TeamMember m where m.user.id = :userId")
    int deleteByUser(@Param("userId") Long userId);
}
