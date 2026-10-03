package com.smartcollab.event;

import java.util.List;

/**
 * 계정·팀·파일을 지울 때 각 모듈이 자기 데이터를 정리하도록 알리는 이벤트들 [A-02].
 * <p>이전에는 회원 탈퇴가 6개 모듈의 리포지토리 10개를, 팀 삭제가 4개 모듈을 직접 지워, 새 테이블이 생기면 지우는 쪽
 * 세 곳을 함께 고쳐야 했고 모듈 순환의 대부분이 여기서 생겼습니다. 이제 지우는 쪽은 이벤트만 발행하고, 데이터를 가진
 * 모듈이 듣고 정리합니다.</p>
 * <ul>
 *   <li>발행한 트랜잭션 안에서 동기로 처리합니다(@EventListener). 하나라도 실패하면 전체가 롤백됩니다.</li>
 *   <li>외래 키 때문에 지우는 순서가 중요하므로, 듣는 쪽은 {@link Order} 의 값을 {@code @Order} 로 붙입니다.</li>
 * </ul>
 */
public final class DeletionEvents {

    private DeletionEvents() {
    }

    /** 계정을 지우기 직전. 개인 자료는 지우고, 팀 자료는 시스템 계정(systemUserId)에게 넘깁니다. */
    public record AccountDeleting(Long userId, Long systemUserId) {
    }

    /** 팀을 지우기 직전. 채팅과 팀 스토리지(폴더·파일·버전·서명·공유 링크)를 지웁니다. */
    public record TeamDeleting(Long teamId) {
    }

    /** 파일 행을 영구 삭제하기 직전(최대 500개씩). 그 파일을 가리키는 서명·공유 링크를 지웁니다. */
    public record FilesPurging(List<Long> fileIds) {
    }

    /** 듣는 쪽의 실행 순서. 외래 키가 가리키는 쪽보다 가리키는 쪽을 먼저 정리합니다. */
    public static final class Order {

        private Order() {
        }

        /** 계정: 팀장인 팀이 남아 있으면 아무것도 지우기 전에 거절 */
        public static final int ACCOUNT_CHECK = 0;
        /** 계정: 개인 스토리지(폴더·파일·버전, 그 파일의 서명·공유 링크) */
        public static final int ACCOUNT_PERSONAL_DRIVE = 100;
        /** 계정: 내가 만든 공유 링크 */
        public static final int ACCOUNT_SHARE_LINKS = 200;
        /** 계정: 내 서명 */
        public static final int ACCOUNT_SIGNATURES = 300;
        /** 계정: 팀 스토리지에서 내가 만든 폴더·파일·버전의 작성자를 시스템 계정으로 */
        public static final int ACCOUNT_TEAM_DRIVE = 400;
        /** 계정: 내 채팅의 보낸 사람을 시스템 계정으로 */
        public static final int ACCOUNT_CHAT = 500;
        /** 계정: 내가 보내거나 받은 초대 */
        public static final int ACCOUNT_INVITATIONS = 600;
        /** 계정: 내 알림 */
        public static final int ACCOUNT_NOTIFICATIONS = 700;
        /** 계정: 팀 멤버십(빠진 팀에 실시간 변경 알림) */
        public static final int ACCOUNT_MEMBERSHIPS = 800;

        /** 팀: 채팅 */
        public static final int TEAM_CHAT = 100;
        /** 팀: 팀 스토리지 */
        public static final int TEAM_DRIVE = 200;
    }
}
