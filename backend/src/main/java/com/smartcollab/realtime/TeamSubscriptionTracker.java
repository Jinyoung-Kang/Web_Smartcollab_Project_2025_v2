package com.smartcollab.realtime;

import com.smartcollab.event.ChangeEvents;
import com.smartcollab.global.security.AuthUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;

/**
 * 팀 토픽(/topic/teams/{id}/chat·events·presence) 구독을 (세션, 구독) 단위로 추적합니다.
 * <ul>
 *   <li>접속 표시: presence 토픽을 구독한 세션의 사용자를 "접속 중"으로 봅니다. 같은 사람이 탭을 여러 개 열었다가
 *       하나만 닫아도 오프라인으로 바뀌지 않습니다 (v1 은 사용자 이름 집합만 두어 탭 하나를 닫으면 퇴장 처리).</li>
 *   <li>권한 회수 [SEC-02]: 구독 권한은 SUBSCRIBE 순간에만 검사되므로, 팀에서 제외되거나 탈퇴한 사용자의 기존 구독은
 *       커밋 이후 브로커에서 직접 해제합니다. 연결은 끊지 않아 개인 알림 등 다른 구독은 그대로 유지됩니다.</li>
 * </ul>
 * <p>구독 이벤트와 브로커 등록은 비동기로 처리되므로, 회수와 거의 같은 순간에 들어온 SUBSCRIBE 는 드물게 남을 수 있습니다.
 * 그런 구독도 다음 재연결 때 SUBSCRIBE 검사에서 거절됩니다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TeamSubscriptionTracker {

    private final SimpMessagingTemplate messaging;
    private final ObjectProvider<SimpleBrokerMessageHandler> broker;

    private record TeamSubscription(Long teamId, boolean presence) {
    }

    private record SessionUser(Long id, String username) {
    }

    /** sessionId → (subscriptionId → 팀 구독) */
    private final Map<String, Map<String, TeamSubscription>> subscriptions = new ConcurrentHashMap<>();
    /** sessionId → 사용자 */
    private final Map<String, SessionUser> sessionUsers = new ConcurrentHashMap<>();

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();
        AuthUser user = StompAuthorizationInterceptor.authUser(event.getUser());
        if (destination == null || user == null) return;
        Matcher m = StompAuthorizationInterceptor.TEAM_TOPIC.matcher(destination);
        if (!m.matches()) return;
        TeamSubscription sub = new TeamSubscription(Long.valueOf(m.group(1)), "presence".equals(m.group(2)));
        sessionUsers.put(accessor.getSessionId(), new SessionUser(user.id(), user.username()));
        subscriptions.computeIfAbsent(accessor.getSessionId(), k -> new ConcurrentHashMap<>())
                .put(accessor.getSubscriptionId(), sub);
        if (sub.presence()) broadcast(sub.teamId());
    }

    @EventListener
    public void onUnsubscribe(SessionUnsubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Map<String, TeamSubscription> subs = subscriptions.get(accessor.getSessionId());
        if (subs == null) return;
        TeamSubscription removed = subs.remove(accessor.getSubscriptionId());
        if (removed != null && removed.presence()) broadcast(removed.teamId());
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        Map<String, TeamSubscription> subs = subscriptions.remove(event.getSessionId());
        sessionUsers.remove(event.getSessionId());
        if (subs == null) return;
        subs.values().stream().filter(TeamSubscription::presence).map(TeamSubscription::teamId)
                .distinct().forEach(this::broadcast);
    }

    /** 팀에서 제외·탈퇴가 커밋되면 그 사용자의 해당 팀 구독을 브로커에서 해제합니다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMembershipRevoked(ChangeEvents.MembershipRevoked event) {
        SimpleBrokerMessageHandler handler = broker.getIfAvailable();
        Set<Long> presenceChanged = new HashSet<>();
        sessionUsers.forEach((sessionId, user) -> {
            if (!user.id().equals(event.userId())) return;
            // 같은 순간 연결이 끊겨 구독 목록이 먼저 지워졌을 수 있습니다.
            Map<String, TeamSubscription> subs = subscriptions.get(sessionId);
            if (subs == null) return;
            subs.entrySet().removeIf(entry -> {
                if (!entry.getValue().teamId().equals(event.teamId())) return false;
                if (handler != null) {
                    handler.getSubscriptionRegistry().unregisterSubscription(unsubscribeMessage(sessionId, entry.getKey()));
                }
                if (entry.getValue().presence()) presenceChanged.add(event.teamId());
                return true;
            });
        });
        if (handler == null) {
            log.warn("Simple broker not available; subscriptions of user {} in team {} not revoked", event.userId(), event.teamId());
        }
        presenceChanged.forEach(this::broadcast);
    }

    public List<String> onlineUsers(Long teamId) {
        Set<String> online = new TreeSet<>();
        subscriptions.forEach((sessionId, subs) -> {
            boolean present = subs.values().stream().anyMatch(s -> s.presence() && s.teamId().equals(teamId));
            SessionUser user = sessionUsers.get(sessionId);
            if (present && user != null) online.add(user.username());
        });
        return List.copyOf(online);
    }

    private static Message<byte[]> unsubscribeMessage(String sessionId, String subscriptionId) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.UNSUBSCRIBE);
        accessor.setSessionId(sessionId);
        accessor.setSubscriptionId(subscriptionId);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private void broadcast(Long teamId) {
        messaging.convertAndSend(RealtimePublisher.presenceTopic(teamId), (Object) Map.of("online", onlineUsers(teamId)));
    }
}
