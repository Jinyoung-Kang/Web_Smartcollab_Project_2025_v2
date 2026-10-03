package com.smartcollab.team;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    long countByOwnerId(Long ownerId);

    @Query("select t.id from Team t where t.owner.id = :ownerId")
    List<Long> findIdsOwnedBy(@Param("ownerId") Long ownerId);

    @Query("select t.name from Team t where t.owner.id = :ownerId order by t.name")
    List<String> findNamesOwnedBy(@Param("ownerId") Long ownerId);
}
