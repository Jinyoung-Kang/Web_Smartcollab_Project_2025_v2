package com.smartcollab.user;

public enum Role {
    USER,
    /** 탈퇴한 사용자의 팀 데이터 소유권을 넘겨받는 시스템 계정. 로그인할 수 없습니다. */
    SYSTEM
}
