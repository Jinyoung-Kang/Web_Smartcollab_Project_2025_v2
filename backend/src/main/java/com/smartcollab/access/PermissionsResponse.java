package com.smartcollab.access;

/** 화면이 버튼을 켜고 끄는 데 쓰는 내 권한. 폴더 내용·팀 목록·팀 상세 응답에 함께 실립니다. */
public record PermissionsResponse(boolean canEdit, boolean canDelete, boolean canInvite, boolean leader) {

    public static PermissionsResponse of(Access access) {
        return new PermissionsResponse(access.canEdit(), access.canDelete(), access.canInvite(), access.leader());
    }
}
