package com.smartcollab.notification;

import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [PERF-04] 알림 목록은 "내 알림 최신순 30개"를 매번 조회합니다. 기존 인덱스 (user_id, is_read, created_at) 는
 * 읽음 여부가 중간에 끼어 있어 최신순 정렬에 쓰이지 못하고, 사용자의 알림 전체를 읽어 정렬(filesort)했습니다.
 */
class NotificationQueryPlanTest extends IntegrationTest {

    @Test
    @DisplayName("최근 알림 조회는 정렬 없이 인덱스 순서대로 읽는다")
    void recentNotificationsUseIndexOrder() {
        Api.Session s = api().signUp("notiplan");
        // 실제처럼 한 사용자의 알림이 전체의 일부가 되도록 다른 사용자 20명의 알림도 넣습니다
        // (한 사용자의 알림이 테이블의 전부면 옵티마이저는 인덱스보다 전체 읽기를 고릅니다).
        List<Long> owners = new ArrayList<>(List.of(s.userId));
        for (int u = 0; u < 20; u++) {
            String name = s.username + "_n" + u;
            jdbc.update("insert into users (username, password, name, role, created_at) values (?, 'x', ?, 'USER', now(6))", name, name);
            owners.add(jdbc.queryForObject("select user_id from users where username = ?", Long.class, name));
        }
        List<Object[]> rows = new ArrayList<>();
        for (Long owner : owners) {
            // 알림은 자동으로 지우지 않아 오래 쓴 사용자에게는 수천 개가 쌓입니다
            int count = owner.equals(s.userId) ? 3000 : 200;
            for (int i = 0; i < count; i++) {
                rows.add(new Object[]{owner, "TEAM_INVITE", "알림 " + i, i % 3 == 0, Timestamp.from(Instant.now().minusSeconds(i))});
            }
        }
        jdbc.batchUpdate("insert into notifications (user_id, type, content, is_read, created_at) values (?, ?, ?, ?, ?)", rows);
        jdbc.execute("analyze table notifications");

        // NotificationRepository.findRecent 와 같은 조건·정렬
        Map<String, Object> plan = jdbc.queryForList("""
                explain select * from notifications n
                where n.user_id = ? order by n.created_at desc, n.notification_id desc limit 30""", s.userId).getFirst();

        assertThat(plan.get("key")).as("실행 계획 %s", plan).isEqualTo("idx_notifications_user_recent");
        assertThat(String.valueOf(plan.get("Extra"))).doesNotContain("filesort");
    }
}
