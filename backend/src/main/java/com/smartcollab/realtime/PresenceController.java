package com.smartcollab.realtime;

import com.smartcollab.access.AccessPolicy;
import com.smartcollab.global.security.AuthUser;
import com.smartcollab.global.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 팀 접속 현황. 접속 여부는 실시간 연결(구독)에서 나오므로 실시간 모듈에 둡니다 [A-04] — 이전에는 팀 컨트롤러가
 * 구독 추적기를 직접 불러 팀 모듈이 실시간 모듈에 의존했습니다. 주소와 응답은 그대로입니다.
 */
@Tag(name = "Teams", description = "팀·팀원·권한")
@RestController
@RequiredArgsConstructor
public class PresenceController {

    private final AccessPolicy accessPolicy;
    private final TeamSubscriptionTracker subscriptions;

    @Operation(summary = "지금 접속 중인 팀원")
    @GetMapping("/api/teams/{teamId}/presence")
    public PresenceResponse presence(@PathVariable Long teamId, @CurrentUser AuthUser user) {
        accessPolicy.requireMember(teamId, user.id());
        return new PresenceResponse(subscriptions.onlineUsers(teamId));
    }

    public record PresenceResponse(List<String> online) {
    }
}
