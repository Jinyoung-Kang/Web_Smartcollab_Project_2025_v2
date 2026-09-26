package com.smartcollab.global.security;

/**
 * 인증된 요청의 사용자 식별 정보. JWT 클레임에서 만들어지므로 DB 조회가 필요 없습니다.
 */
public record AuthUser(Long id, String username) {
}
