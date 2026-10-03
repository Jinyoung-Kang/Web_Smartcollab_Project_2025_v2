package com.smartcollab.user;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    /** 행 잠금(SELECT … FOR UPDATE). 같은 사용자의 저장 한도 확인을 줄 세우는 데 씁니다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> lockById(@Param("id") Long id);

    /**
     * 개인 저장 공간의 사용량 집계(옛 버전·휴지통 포함) [IMP-01]. 엔티티에 두지 않는 것은, 다른 변경으로 사용자 엔티티를 저장할 때
     * 오래된 값으로 덮어쓰지 않도록 집계는 항상 아래 UPDATE 로만 바꾸기 위함입니다.
     */
    @Query(value = "SELECT stored_bytes FROM users WHERE user_id = :id", nativeQuery = true)
    Optional<Long> storedBytes(@Param("id") Long id);

    @Modifying
    @Query(value = "UPDATE users SET stored_bytes = stored_bytes + :delta WHERE user_id = :id", nativeQuery = true)
    int addStoredBytes(@Param("id") Long id, @Param("delta") long delta);

    @Modifying
    @Query(value = "UPDATE users SET stored_bytes = :bytes WHERE user_id = :id", nativeQuery = true)
    int setStoredBytes(@Param("id") Long id, @Param("bytes") long bytes);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    Optional<User> findFirstByRole(Role role);
}
