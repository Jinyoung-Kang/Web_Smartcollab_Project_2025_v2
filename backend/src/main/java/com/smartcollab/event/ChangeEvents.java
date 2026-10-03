package com.smartcollab.event;

/**
 * 업무 모듈이 "무엇이 바뀌었는지" 알리는 변경 이벤트들. 커밋된 뒤 실시간 모듈(realtime)이 WebSocket 으로 내보냅니다.
 * <p>업무 모듈은 이벤트만 발행하고 전송 방법은 모릅니다. 이벤트를 실시간 패키지에 두면 업무 규칙이 바깥 어댑터에
 * 의존하게 되어(방향 역전) 중립 패키지로 옮겼습니다 [A-03]. 롤백된 변경이 다른 사용자 화면에 먼저 반영되지 않도록
 * 전송은 커밋 이후에만 합니다.</p>
 */
public final class ChangeEvents {

    private ChangeEvents() {
    }

    /** 팀 폴더 내용이 바뀜 → 같은 폴더를 보고 있는 팀원 화면을 갱신 */
    public record FolderChanged(Long teamId, Long folderId) {
    }

    /** 팀 구성·권한·이름이 바뀜 */
    public record TeamChanged(Long teamId, TeamChangeType change) {
    }

    public enum TeamChangeType {MEMBERS_CHANGED, TEAM_DELETED, CHAT_CLEARED}

    /** 사용자가 팀에서 빠짐(제외·나가기·탈퇴) → 그 사용자의 열린 팀 구독을 해제 [SEC-02] */
    public record MembershipRevoked(Long teamId, Long userId) {
    }

    /** 특정 사용자에게 보내는 알림 */
    public record UserNotified(String username, Object payload) {
    }

    /** 팀 채팅 메시지 */
    public record ChatPosted(Long teamId, Object payload) {
    }
}
