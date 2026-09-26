package com.smartcollab.chat;

import com.smartcollab.access.AccessPolicy;
import com.smartcollab.file.FileEntity;
import com.smartcollab.file.FileRepository;
import com.smartcollab.global.error.ApiException;
import com.smartcollab.realtime.RealtimeEvents;
import com.smartcollab.team.TeamMember;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ChatService {

    static final int DEFAULT_PAGE = 30;
    static final int MAX_PAGE = 100;

    private final ChatMessageRepository messages;
    private final FileRepository files;
    private final AccessPolicy accessPolicy;
    private final ApplicationEventPublisher events;

    /**
     * 커서 기반 페이지네이션. v1 은 팀의 전체 채팅 기록을 한 번에 내려보냈고 멤버 여부도 확인하지 않았습니다.
     * OFFSET 방식과 달리 새 메시지가 계속 쌓여도 페이지가 밀리거나 중복되지 않습니다.
     */
    @Transactional(readOnly = true)
    public ChatDtos.Page history(Long teamId, Long before, Integer size, Long userId) {
        accessPolicy.requireMember(teamId, userId);
        int limit = size == null ? DEFAULT_PAGE : Math.clamp(size, 1, MAX_PAGE);
        long cursor = before == null ? Long.MAX_VALUE : before;
        List<ChatMessage> page = messages.findPage(teamId, cursor, PageRequest.of(0, limit + 1));
        boolean hasMore = page.size() > limit;
        List<ChatDtos.MessageResponse> result = new ArrayList<>(page.subList(0, Math.min(limit, page.size())).stream()
                .map(ChatDtos.MessageResponse::of).toList());
        Collections.reverse(result);   // 화면에는 오래된 것 → 최신 순
        return new ChatDtos.Page(result, hasMore);
    }

    @Transactional
    public ChatDtos.MessageResponse post(Long teamId, ChatDtos.SendRequest req, Long userId) {
        TeamMember member = accessPolicy.requireMember(teamId, userId);
        ChatMessage message;
        if (req.fileId() != null) {
            FileEntity file = files.findWithFolder(req.fileId())
                    .filter(f -> !f.isDeleted() && Objects.equals(f.getFolder().teamId(), teamId))
                    .orElseThrow(() -> ApiException.notFound("이 팀의 파일"));
            message = ChatMessage.fileShare(member.getTeam(), member.getUser(), file.getId(), file.getName(), file.getSize());
        } else {
            String content = req.content() == null ? "" : req.content().strip();
            if (content.isEmpty()) {
                throw ApiException.badRequest("메시지를 입력하세요.");
            }
            if (content.length() > ChatMessage.MAX_LENGTH) {
                throw ApiException.badRequest("메시지는 " + ChatMessage.MAX_LENGTH + "자 이하로 입력하세요.");
            }
            message = ChatMessage.text(member.getTeam(), member.getUser(), content);
        }
        ChatDtos.MessageResponse response = ChatDtos.MessageResponse.of(messages.save(message));
        events.publishEvent(new RealtimeEvents.ChatPosted(teamId, response));
        return response;
    }

    @Transactional
    public void clear(Long teamId, Long userId) {
        accessPolicy.requireLeader(teamId, userId);
        messages.deleteByTeam(teamId);
        events.publishEvent(new RealtimeEvents.TeamChanged(teamId, RealtimeEvents.TeamChangeType.CHAT_CLEARED));
    }
}
