package com.smartcollab.team;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TeamRepository extends JpaRepository<Team, Long> {

    @Query("select t from Team t join fetch t.owner where t.id = :id")
    Optional<Team> findWithOwner(@Param("id") Long id);

    /** 행 잠금(SELECT … FOR UPDATE). 같은 팀의 저장 한도 확인을 줄 세우는 데 씁니다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Team t where t.id = :id")
    Optional<Team> lockById(@Param("id") Long id);

    /** 팀 저장 공간의 사용량 집계(옛 버전·휴지통 포함) — UserRepository.storedBytes 와 같은 방식 [IMP-01] */
    @Query(value = "SELECT stored_bytes FROM teams WHERE team_id = :id", nativeQuery = true)
    Optional<Long> storedBytes(@Param("id") Long id);

    @Modifying
    @Query(value = "UPDATE teams SET stored_bytes = stored_bytes + :delta WHERE team_id = :id", nativeQuery = true)
    int addStoredBytes(@Param("id") Long id, @Param("delta") long delta);

    @Modifying
    @Query(value = "UPDATE teams SET stored_bytes = :bytes WHERE team_id = :id", nativeQuery = true)
    int setStoredBytes(@Param("id") Long id, @Param("bytes") long bytes);

    long countByOwnerId(Long ownerId);

    @Query("select t.id from Team t where t.owner.id = :ownerId")
    List<Long> findIdsOwnedBy(@Param("ownerId") Long ownerId);

    @Query("select t.name from Team t where t.owner.id = :ownerId order by t.name")
    List<String> findNamesOwnedBy(@Param("ownerId") Long ownerId);
}
