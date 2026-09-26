package com.smartcollab.user;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    /** 행 잠금(SELECT … FOR UPDATE). 같은 사용자의 저장 한도 확인을 줄 세우는 데 씁니다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> lockById(@Param("id") Long id);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    Optional<User> findFirstByRole(Role role);
}
