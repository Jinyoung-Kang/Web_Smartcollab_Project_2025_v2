package com.smartcollab.event;

/**
 * 사용자에게 남길 알림(초대·권한 변경·팀 삭제 등). 팀 모듈이 발행하고 알림 모듈이 같은 트랜잭션 안에서 동기로 저장합니다 [A-01].
 * 이전에는 팀 모듈이 알림 서비스를 직접 불러, 알림(→ 팀) 과 팀(→ 알림) 이 서로 의존했습니다.
 */
public final class NoticeEvents {

    private NoticeEvents() {
    }

    /** 알림 종류. 이름이 DB 값·API 응답의 type 그대로입니다. */
    public enum Type {
        TEAM_INVITE,
        INVITE_ACCEPTED,
        INVITE_REJECTED,
        PERMISSION_CHANGED,
        REMOVED_FROM_TEAM,
        LEADERSHIP_TRANSFERRED,
        TEAM_DELETED
    }

    /** 알림 요청. invitationId·teamId 는 없으면 null 입니다(팀 삭제 알림은 팀이 이미 지워져 null). */
    public record Requested(Long recipientUserId, Type type, String content, Long invitationId, Long teamId) {
    }
}
