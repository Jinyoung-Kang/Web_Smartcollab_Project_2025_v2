package com.smartcollab.realtime;

import com.smartcollab.global.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

import java.util.List;

/**
 * STOMP over WebSocket 설정.
 * <p>핸드셰이크(/ws)는 일반 HTTP 요청이라 인증 쿠키로 Spring Security 가 먼저 인증하고,
 * 그 사용자가 STOMP 세션의 Principal 이 됩니다. 구독·전송 권한은 {@link StompAuthorizationInterceptor} 가 검사합니다.</p>
 * <p>v1 은 팀마다 WebSocket 연결을 따로 열었지만(팀 N개 = 연결 N개), v2 는 연결 1개에 구독만 여러 개 둡니다.</p>
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final AppProperties props;
    private final StompAuthorizationInterceptor authorizationInterceptor;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        List<String> origins = props.cors().allowedOrigins() == null ? List.of() : props.cors().allowedOrigins();
        // 같은 출처(same-origin)는 항상 허용되고, 개발 서버(Vite) 등 추가 출처만 여기에 등록합니다.
        registry.addEndpoint("/ws").setAllowedOriginPatterns(origins.toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        ThreadPoolTaskScheduler heartbeat = new ThreadPoolTaskScheduler();
        heartbeat.setPoolSize(1);
        heartbeat.setThreadNamePrefix("ws-heartbeat-");
        heartbeat.initialize();

        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[]{20_000, 20_000})
                .setTaskScheduler(heartbeat);
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authorizationInterceptor);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(16 * 1024);
    }
}
