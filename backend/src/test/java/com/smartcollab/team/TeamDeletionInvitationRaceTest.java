package com.smartcollab.team;

import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [L-6 후속] 팀 삭제는 팀 행을 먼저 잠그고 초대를 지웁니다. 초대 수락·거절은 초대 행을 먼저 잠근 뒤 멤버·알림을 넣으며 팀 행의
 * 외래 키 확인(공유 잠금)을 기다립니다. 둘이 겹치면 서로를 기다려 MySQL 이 한쪽을 교착(1213)으로 끊었습니다 — 처리기 없는 500.
 * 수락·거절도 팀 행부터 잠그면(같은 순서) 삭제가 끝난 뒤 "초대 없음"으로 끝납니다.
 */
class TeamDeletionInvitationRaceTest extends IntegrationTest {

    @Autowired
    TeamService teamService;
    @Autowired
    TeamRepository teams;
    @Autowired
    InvitationRepository invitations;
    @Autowired
    PlatformTransactionManager txManager;

    @Test
    @DisplayName("[L-6 후속] 팀을 지우는 동안 초대를 수락해도 교착 없이 삭제가 끝나고, 수락은 '초대 없음'으로 끝난다")
    void deleteTeamWhileAcceptingInvitation() throws Exception {
        Api.Session leader = api().signUp("l6i");
        Api.Session invitee = api().signUp("l6j");
        long teamId = leader.createTeam("초대 경합 팀")[0];
        leader.postJson("/api/teams/{t}/invitations", Map.of("username", invitee.username), teamId).andExpect(status().isCreated());
        long invitationId = ((Number) Api.read(invitee.get("/api/notifications"), "$.items[0].invitationId")).longValue();

        TransactionTemplate readCommitted = new TransactionTemplate(txManager);   // 팀 삭제와 같은 격리 수준
        readCommitted.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        CountDownLatch teamLocked = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            // 팀 삭제가 팀 행을 잠근 직후, 초대 수락이 끼어들 틈을 둔 뒤 나머지를 지웁니다
            Future<Throwable> deletion = pool.submit(() -> capture(() -> readCommitted.executeWithoutResult(s -> {
                teams.lockById(teamId);
                teamLocked.countDown();
                sleep(1000);
                teamService.purgeTeam(teamId, leader.userId);
            })));
            assertThat(teamLocked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> accept = pool.submit(() -> capture(() -> teamService.respondToInvitation(invitationId, true, invitee.userId)));

            Throwable deleted = deletion.get(30, TimeUnit.SECONDS);
            Throwable accepted = accept.get(30, TimeUnit.SECONDS);

            assertThat(deleted).as("팀 삭제 결과").isNull();
            assertThat(accepted).as("초대 수락 결과").isNotInstanceOf(PessimisticLockingFailureException.class)
                    .hasMessageContaining("초대");
        }
        assertThat(teams.findById(teamId)).isEmpty();
        assertThat(invitations.findById(invitationId)).isEmpty();
    }

    private static Throwable capture(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable e) {
            return e;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
