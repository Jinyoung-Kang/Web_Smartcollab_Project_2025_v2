package com.smartcollab.team;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TeamRepository extends JpaRepository<Team, Long> {

    @Query("select t from Team t join fetch t.owner where t.id = :id")
    Optional<Team> findWithOwner(@Param("id") Long id);

    @Query("select t.name from Team t where t.owner.id = :ownerId order by t.name")
    List<String> findNamesOwnedBy(@Param("ownerId") Long ownerId);
}
