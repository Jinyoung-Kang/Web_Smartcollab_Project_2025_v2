package com.smartcollab.user;

public enum Role {
    USER,
    /** 탈퇴한 사용자의 팀 데이터 소유권을 넘겨받는 시스템 계정. 로그인할 수 없습니다. */
    SYSTEM,
    /**
     * 관리 전용 계정 — 계정 목록(아이디·이름·소속 팀·사용량·가입일)을 보고 계정을 삭제만 합니다. 드라이브·팀·채팅이 없고 그 API 도 쓸 수 없습니다.
     * 환경 변수(ADMIN_USERNAME·ADMIN_PASSWORD)로만 만들어집니다 ({@link AdminAccountInitializer}, ADR-0015).
     */
    ADMIN
}
