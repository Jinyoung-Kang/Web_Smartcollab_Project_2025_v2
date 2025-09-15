package com.smartcollab.prod.util;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.function.Function;

// JWT(JSON Web Token) 생성 및 검증을 담당하는 유틸리티 클래스
@Component
@Slf4j
public class JwtUtil {

    @Value("${jwt.secret-key}")
    private String secret;

    private Key secretKey;

    // 8시간
    private final long expirationTime = 1000L * 60 * 60 * 12;

    // 의존성 주입 후 비밀 키 초기화
    @PostConstruct
    public void init() {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 토큰에서 모든 클레임(정보)을 추출
     * @param token JWT 토큰
     * @return 토큰에 담긴 Claims
     */
    private Claims extractAllClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(secretKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    /**
     * 토큰에서 특정 클레임을 추출
     * @param token JWT 토큰
     * @param claimsResolver 클레임을 가져오는 함수
     * @return 특정 클레임 값
     */
    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    /**
     * 토큰에서 사용자 이름(subject)을 추출
     * @param token JWT 토큰
     * @return 사용자 이름
     */
    public String getUsernameFromToken(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    /**
     * 토큰 만료 여부를 확인
     * @param token JWT 토큰
     * @return 만료되었으면 true, 아니면 false
     */
    private Boolean isTokenExpired(String token) {
        return extractClaim(token, Claims::getExpiration).before(new Date());
    }

    /**
     * 사용자 정보를 기반으로 JWT 토큰을 생성
     * @param username 사용자 이름
     * @return 생성된 JWT 토큰
     */
    public String generateToken(String username) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expirationTime);

        return Jwts.builder()
                .setSubject(username)
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                .signWith(secretKey, SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * 토큰의 유효성을 검증
     * @param token JWT 토큰
     * @param userDetails 사용자 상세 정보
     * @return 유효하면 true, 아니면 false
     */
    public Boolean validateToken(String token, UserDetails userDetails) {
        final String username = getUsernameFromToken(token);
        return (username.equals(userDetails.getUsername()) && !isTokenExpired(token));
    }
}
