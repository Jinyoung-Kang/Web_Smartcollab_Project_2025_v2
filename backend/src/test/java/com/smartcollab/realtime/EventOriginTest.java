package com.smartcollab.realtime;

import com.smartcollab.event.ChangeEvents;
import com.smartcollab.global.web.RequestOrigin;
import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.AbstractSubscribableChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [IMP-03] 팀 폴더에서 내가 바꾸면 같은 목록을 두 번 불러왔습니다(변경 요청의 응답 + 내 변경의 실시간 이벤트). 요청의 탭 ID(X-Client-Id)를
 * 폴더 변경 이벤트에 실어, 보낸 탭이 자기 변경을 알아보게 합니다.
 */
class EventOriginTest extends IntegrationTest {

    @Autowired
    @Qualifier("brokerChannel")
    AbstractSubscribableChannel brokerChannel;

    @Test
    @DisplayName("[IMP-03] 요청의 X-Client-Id 가 그 요청이 일으킨 폴더 변경 이벤트에 실린다")
    void folderChangeCarriesOrigin() throws Exception {
        Api.Session s = api().signUp("imp03");
        long[] team = s.createTeam("출처 팀");
        List<String> payloads = new CopyOnWriteArrayList<>();
        ChannelInterceptor capture = new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                String destination = SimpMessageHeaderAccessor.getDestination(message.getHeaders());
                if (RealtimePublisher.eventsTopic(team[0]).equals(destination) && message.getPayload() instanceof byte[] bytes) {
                    payloads.add(new String(bytes, StandardCharsets.UTF_8));
                }
                return message;
            }
        };
        brokerChannel.addInterceptor(capture);
        try {
            s.send(MockMvcRequestBuilders.post("/api/folders").header("X-Client-Id", "tab-1234abcd")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"parentId\":" + team[1] + ",\"name\":\"새 폴더\"}"))
                    .andExpect(status().isCreated());
        } finally {
            brokerChannel.removeInterceptor(capture);
        }

        assertThat(payloads).anySatisfy(p -> assertThat(json.readValue(p, Map.class))
                .containsEntry("type", "FOLDER_CHANGED").containsEntry("origin", "tab-1234abcd"));
    }

    @Test
    @DisplayName("[IMP-03] 탭 ID 가 없거나 형식이 틀리면 이벤트에 싣지 않는다")
    void invalidOriginIsDropped() {
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        RealtimePublisher publisher = new RealtimePublisher(messaging);

        publisher.on(new ChangeEvents.FolderChanged(1L, 2L));
        try (RequestOrigin.Scope ignored = RequestOrigin.bind("<script>alert(1)</script>")) {
            publisher.on(new ChangeEvents.FolderChanged(1L, 3L));
        }

        verify(messaging).convertAndSend(eq(RealtimePublisher.eventsTopic(1L)), eq((Object) Map.of("type", "FOLDER_CHANGED", "folderId", 2L)));
        verify(messaging).convertAndSend(eq(RealtimePublisher.eventsTopic(1L)), eq((Object) Map.of("type", "FOLDER_CHANGED", "folderId", 3L)));
    }
}
