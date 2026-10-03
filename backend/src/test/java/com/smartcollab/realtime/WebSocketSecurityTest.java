package com.smartcollab.realtime;

import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 서버(랜덤 포트)에 WebSocket(STOMP)으로 접속해 인증·인가를 검증합니다.
 * v1 은 /ws 가 permitAll 이고 STOMP 프레임에 권한 검사가 없어, 누구나 아무 팀 채팅을 구독하고
 * 페이로드의 sender 값을 바꿔 다른 사람 이름으로 메시지를 보낼 수 있었습니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketSecurityTest extends IntegrationTest {

    @LocalServerPort
    int port;

    WebSocketStompClient client;

    @BeforeEach
    void setUp() {
        client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter());
    }

    @AfterEach
    void tearDown() {
        client.stop();
    }

    private StompSession connect(Api.Session user) throws Exception {
        return connect(user, new StompSessionHandlerAdapter() {
        });
    }

    private StompSession connect(Api.Session user, StompSessionHandlerAdapter handler) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        if (user != null) {
            headers.add("Cookie", "SC_AUTH=" + user.cookie.getValue());
        }
        return client.connectAsync("ws://localhost:" + port + "/ws", headers, handler).get(5, TimeUnit.SECONDS);
    }

    /** 서버가 보낸 ERROR 프레임의 message 헤더를 모읍니다. */
    private static StompSessionHandlerAdapter collectingErrors(BlockingQueue<String> errors) {
        return new StompSessionHandlerAdapter() {
            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                errors.add(String.valueOf(headers.getFirst("message")));
            }

            @Override
            public void handleException(StompSession session, StompCommand command, StompHeaders headers, byte[] payload,
                                        Throwable exception) {
                errors.add(String.valueOf(headers.getFirst("message")));
            }
        };
    }

    private static BlockingQueue<Map<String, Object>> subscribe(StompSession session, String destination) {
        BlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                queue.add((Map<String, Object>) payload);
            }
        });
        return queue;
    }

    private long[] teamWith(Api.Session leader, Api.Session member) throws Exception {
        long[] team = leader.createTeam("실시간 팀");
        leader.postJson("/api/teams/{t}/invitations", Map.of("username", member.username), team[0]);
        long inv = ((Number) Api.read(member.get("/api/notifications"), "$.items[0].invitationId")).longValue();
        member.post("/api/invitations/{id}/accept", inv);
        return team;
    }

    @Test
    @DisplayName("인증 쿠키 없이 WebSocket 에 연결할 수 없다")
    void anonymousHandshakeIsRejected() {
        assertThatThrownBy(() -> connect(null)).isInstanceOf(ExecutionException.class);
    }

    @Test
    @DisplayName("보낸 사람은 페이로드가 아니라 인증 정보로 정해진다 (사칭 불가)")
    void senderComesFromPrincipal() throws Exception {
        Api.Session leader = api().signUp("wsl");
        Api.Session member = api().signUp("wsm");
        long[] team = teamWith(leader, member);

        StompSession leaderWs = connect(leader);
        BlockingQueue<Map<String, Object>> chat = subscribe(leaderWs, "/topic/teams/" + team[0] + "/chat");
        Thread.sleep(300);

        StompSession memberWs = connect(member);
        memberWs.send("/app/teams/" + team[0] + "/chat", Map.of("content", "안녕하세요", "sender", leader.username));

        Map<String, Object> received = chat.poll(5, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        assertThat(received.get("content")).isEqualTo("안녕하세요");
        @SuppressWarnings("unchecked")
        Map<String, Object> sender = (Map<String, Object>) received.get("sender");
        assertThat(sender.get("username")).isEqualTo(member.username);
    }

    @Test
    @DisplayName("팀원이 아니면 팀 채팅을 구독해도 메시지를 받지 못한다")
    void nonMemberCannotSubscribe() throws Exception {
        Api.Session leader = api().signUp("wsl2");
        Api.Session member = api().signUp("wsm2");
        Api.Session outsider = api().signUp("wso");
        long[] team = teamWith(leader, member);

        StompSession outsiderWs = connect(outsider);
        BlockingQueue<Map<String, Object>> spied = subscribe(outsiderWs, "/topic/teams/" + team[0] + "/chat");
        Thread.sleep(300);
        member.postJson("/api/teams/{t}/messages", Map.of("content", "비밀 이야기"), team[0]);

        assertThat(spied.poll(1500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    @DisplayName("[S-11] 한 연결에서 같은 팀 토픽을 다시 구독하면 ERROR 로 거절한다 — 구독마다 DB 조회·접속자 방송이 늘던 문제")
    void duplicateSubscriptionIsRejected() throws Exception {
        Api.Session leader = api().signUp("wsdup");
        long[] team = leader.createTeam("중복 구독 팀");
        BlockingQueue<String> errors = new LinkedBlockingQueue<>();
        StompSession ws = connect(leader, collectingErrors(errors));
        String presence = "/topic/teams/" + team[0] + "/presence";

        subscribe(ws, presence);
        Thread.sleep(300);
        assertThat(errors).isEmpty();
        subscribe(ws, presence);

        assertThat(errors.poll(5, TimeUnit.SECONDS)).contains("이미 구독 중");
    }

    @Test
    @DisplayName("[SEC-02] 팀에서 내보낸 사용자는 이미 열린 구독으로도 이후 채팅·이벤트를 받지 못한다")
    void removedMemberStopsReceiving() throws Exception {
        Api.Session leader = api().signUp("wsr");
        Api.Session member = api().signUp("wsrm");
        long[] team = teamWith(leader, member);

        StompSession memberWs = connect(member);
        BlockingQueue<Map<String, Object>> chat = subscribe(memberWs, "/topic/teams/" + team[0] + "/chat");
        BlockingQueue<Map<String, Object>> events = subscribe(memberWs, "/topic/teams/" + team[0] + "/events");
        Thread.sleep(300);
        leader.postJson("/api/teams/{t}/messages", Map.of("content", "내보내기 전"), team[0]);
        assertThat(chat.poll(5, TimeUnit.SECONDS)).isNotNull();

        List<Number> memberIds = Api.read(leader.get("/api/teams/{t}", team[0]),
                "$.members[?(@.username == '" + member.username + "')].memberId");
        long memberId = memberIds.getFirst().longValue();
        leader.delete("/api/teams/{t}/members/{m}", team[0], memberId);
        events.clear();
        Thread.sleep(300);

        leader.postJson("/api/teams/{t}/messages", Map.of("content", "내보낸 뒤"), team[0]);
        leader.createFolder(team[1], "내보낸 뒤 만든 폴더");
        assertThat(chat.poll(1500, TimeUnit.MILLISECONDS)).isNull();
        assertThat(events.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    @DisplayName("[SEC-02] 탈퇴한 사용자의 열린 구독으로는 팀 채팅이 전달되지 않는다")
    void deletedAccountStopsReceiving() throws Exception {
        Api.Session leader = api().signUp("wsd");
        Api.Session member = api().signUp("wsdm");
        long[] team = teamWith(leader, member);

        StompSession memberWs = connect(member);
        BlockingQueue<Map<String, Object>> chat = subscribe(memberWs, "/topic/teams/" + team[0] + "/chat");
        Thread.sleep(300);
        member.postJson("/api/users/me/delete", Map.of("password", Api.PASSWORD)).andReturn();
        Thread.sleep(300);

        leader.postJson("/api/teams/{t}/messages", Map.of("content", "탈퇴 뒤"), team[0]);
        assertThat(chat.poll(1500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    @DisplayName("알림은 WebSocket 개인 큐로 즉시 전달된다 (v1: 10초 폴링)")
    void notificationIsPushed() throws Exception {
        Api.Session leader = api().signUp("wsn");
        Api.Session invitee = api().signUp("wsi");
        long[] team = leader.createTeam("알림 팀");

        StompSession inviteeWs = connect(invitee);
        BlockingQueue<Map<String, Object>> inbox = subscribe(inviteeWs, "/user/queue/notifications");
        Thread.sleep(300);
        leader.postJson("/api/teams/{t}/invitations", Map.of("username", invitee.username), team[0]);

        Map<String, Object> pushed = inbox.poll(5, TimeUnit.SECONDS);
        assertThat(pushed).isNotNull();
        assertThat(pushed.get("type")).isEqualTo("TEAM_INVITE");
    }

    @Test
    @DisplayName("접속 중인 팀원 목록(presence)이 갱신된다")
    void presence() throws Exception {
        Api.Session leader = api().signUp("wsp");
        Api.Session member = api().signUp("wspm");
        long[] team = teamWith(leader, member);

        StompSession leaderWs = connect(leader);
        BlockingQueue<Map<String, Object>> presence = subscribe(leaderWs, "/topic/teams/" + team[0] + "/presence");
        Thread.sleep(300);
        StompSession memberWs = connect(member);
        subscribe(memberWs, "/topic/teams/" + team[0] + "/presence");

        Map<String, Object> update;
        do {
            update = presence.poll(5, TimeUnit.SECONDS);
            assertThat(update).isNotNull();
        } while (!((List<?>) update.get("online")).contains(member.username));
        assertThat(update.get("online").toString()).contains(leader.username).contains(member.username);

        memberWs.disconnect();
        do {
            update = presence.poll(5, TimeUnit.SECONDS);
            assertThat(update).isNotNull();
        } while (((List<?>) update.get("online")).contains(member.username));
    }
}
