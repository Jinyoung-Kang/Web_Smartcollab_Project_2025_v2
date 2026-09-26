package com.smartcollab.realtime;

/**
 * 트랜잭션이 커밋된 뒤 WebSocket 으로 내보낼 도메인 이벤트들.
 * 서비스는 이벤트만 발행하고, 전송은 {@link RealtimePublisher} 가 커밋 이후에 담당합니다.
 * (롤백된 변경이 다른 사용자 화면에 먼저 반영되는 일을 막기 위함)
 */
public final class RealtimeEvents {

    private RealtimeEvents() {
    }

    /** 팀 폴더 내용이 바뀜 → 같은 폴더를 보고 있는 팀원 화면을 갱신 */
    public record FolderChanged(Long teamId, Long folderId) {
    }

    /** 팀 구성·권한·이름이 바뀜 */
    public record TeamChanged(Long teamId, TeamChangeType change) {
    }

    public enum TeamChangeType {MEMBERS_CHANGED, TEAM_DELETED, CHAT_CLEARED}

    /** 특정 사용자에게 보내는 알림 */
    public record UserNotified(String username, Object payload) {
    }

    /** 팀 채팅 메시지 */
    public record ChatPosted(Long teamId, Object payload) {
    }
}
