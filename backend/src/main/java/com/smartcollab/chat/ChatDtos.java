package com.smartcollab.chat;

import java.time.Instant;
import java.util.List;

public final class ChatDtos {

    private ChatDtos() {
    }

    /** STOMP 로 받는 전송 요청. 보낸 사람은 서버가 인증 정보로 정합니다(v1 은 페이로드의 sender 를 그대로 믿음). */
    public record SendRequest(String content, Long fileId) {
    }

    public record Sender(String username, String name) {
    }

    public record FileRef(Long id, String name, Long size) {
    }

    public record MessageResponse(Long id, ChatMessage.Type type, String content, Sender sender, FileRef file,
                                  Instant createdAt) {
        public static MessageResponse of(ChatMessage m) {
            FileRef file = m.getType() == ChatMessage.Type.FILE_SHARE
                    ? new FileRef(m.getFileId(), m.getFileName(), m.getFileSize()) : null;
            return new MessageResponse(m.getId(), m.getType(), m.getContent(),
                    new Sender(m.getSender().getUsername(), m.getSender().getName()), file, m.getCreatedAt());
        }
    }

    public record Page(List<MessageResponse> messages, boolean hasMore) {
    }

    public record ErrorResponse(String message) {
    }
}
