package com.smartcollab.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "users")
public class User {

    /** 가입·로그인 아이디 규칙 — 관리자 아이디도 이 규칙에 맞아야 로그인할 수 있습니다 [QA6-09] */
    public static final String USERNAME_REGEX = "^[A-Za-z0-9_.-]{4,20}$";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(nullable = false, length = 100)
    private String password;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(unique = true, length = 100)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public User(String username, String encodedPassword, String name, String email, Role role) {
        this.username = username;
        this.password = encodedPassword;
        this.name = name;
        this.email = email;
        this.role = role;
        this.createdAt = Instant.now();
    }

    public boolean isSystem() {
        return role == Role.SYSTEM;
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    /** 관리자 계정의 비밀번호를 환경 변수 값으로 맞출 때만 씁니다 ({@link AdminAccountInitializer}) */
    void changePassword(String encodedPassword) {
        this.password = encodedPassword;
    }
}
