package com.smartcollab.realtime;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 팀별 "지금 접속 중인 사용자"를 추적합니다.
 * <p>(세션, 구독) 단위로 기록하므로 같은 사람이 탭을 여러 개 열었다가 하나만 닫아도 오프라인으로 바뀌지 않습니다.
 * v1 은 사용자 이름 집합만 두어 탭 하나를 닫으면 다른 탭이 열려 있어도 퇴장 처리됐고, 클라이언트는 이 정보를 쓰지도 않았습니다.</p>
 */
@Component
@RequiredArgsConstructor
public class PresenceTracker {

    private static final Pattern PRESENCE_TOPIC = Pattern.compile("^/topic/teams/(\\d+)/presence$");

    private final SimpMessagingTemplate messaging;

    /** sessionId → (subscriptionId → teamId) */
    private final Map<String, Map<String, Long>> subscriptions = new ConcurrentHashMap<>();
    /** sessionId → username */
    private final Map<String, String> sessionUsers = new ConcurrentHashMap<>();

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();
        Principal user = event.getUser();
        if (destination == null || user == null) return;
        Matcher m = PRESENCE_TOPIC.matcher(destination);
        if (!m.matches()) return;
        Long teamId = Long.valueOf(m.group(1));
        String username = StompAuthorizationInterceptor.authUser(user) != null
                ? StompAuthorizationInterceptor.authUser(user).username() : user.getName();
        sessionUsers.put(accessor.getSessionId(), username);
        subscriptions.computeIfAbsent(accessor.getSessionId(), k -> new ConcurrentHashMap<>())
                .put(accessor.getSubscriptionId(), teamId);
        broadcast(teamId);
    }

    @EventListener
    public void onUnsubscribe(SessionUnsubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Map<String, Long> subs = subscriptions.get(accessor.getSessionId());
        if (subs == null) return;
        Long teamId = subs.remove(accessor.getSubscriptionId());
        if (teamId != null) broadcast(teamId);
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        Map<String, Long> subs = subscriptions.remove(event.getSessionId());
        sessionUsers.remove(event.getSessionId());
        if (subs == null) return;
        Set.copyOf(subs.values()).forEach(this::broadcast);
    }

    public List<String> onlineUsers(Long teamId) {
        Set<String> online = new TreeSet<>();
        subscriptions.forEach((sessionId, subs) -> {
            if (subs.containsValue(teamId)) {
                String username = sessionUsers.get(sessionId);
                if (username != null) online.add(username);
            }
        });
        return List.copyOf(online);
    }

    private void broadcast(Long teamId) {
        messaging.convertAndSend(RealtimePublisher.presenceTopic(teamId), (Object) Map.of("online", onlineUsers(teamId)));
    }
}
