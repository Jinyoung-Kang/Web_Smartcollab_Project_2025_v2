package com.smartcollab.chat;

import com.smartcollab.event.DeletionEvents;
import com.smartcollab.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 계정·팀 삭제 때 채팅을 정리합니다 [A-02]. 지우는 쪽의 트랜잭션 안에서 동기로 실행됩니다. */
@Component
@RequiredArgsConstructor
class ChatDeletionListener {

    private final ChatMessageRepository chatMessages;
    private final UserRepository users;

    /** 팀 채팅은 팀 자료이므로 남기고, 보낸 사람을 시스템 계정("탈퇴한 사용자")으로 바꿉니다. */
    @EventListener
    @Order(DeletionEvents.Order.ACCOUNT_CHAT)
    void transferMessages(DeletionEvents.AccountDeleting event) {
        chatMessages.transferSender(event.userId(), users.getReferenceById(event.systemUserId()));
    }

    @EventListener
    @Order(DeletionEvents.Order.TEAM_CHAT)
    void deleteTeamChat(DeletionEvents.TeamDeleting event) {
        chatMessages.deleteByTeam(event.teamId());
    }
}
