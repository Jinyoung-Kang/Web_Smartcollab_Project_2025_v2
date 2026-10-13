package com.smartcollab.admin;

import com.smartcollab.global.error.ApiException;
import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import com.smartcollab.support.TransactionRace;
import com.smartcollab.team.TeamRepository;
import com.smartcollab.team.TeamService;
import com.smartcollab.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 계정 삭제(ADR-0015)와 겹치는 요청의 경합 — 다른 트랜잭션이 잠금을 쥔 채 기다리는 동안 삭제를 보내 순서를 결정적으로 재현합니다.
 */
@ExtendWith(OutputCaptureExtension.class)
class AdminDeletionRaceTest extends IntegrationTest {

    @Autowired
    AdminService adminService;
    @Autowired
    TeamService teamService;
    @Autowired
    TeamRepository teams;
    @Autowired
    UserRepository users;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    PlatformTransactionManager txManager;

    private long adminId() {
        return jdbc.queryForObject("select user_id from users where username = ?", Long.class, ADMIN_USERNAME);
    }

    private long memberId(long teamId, long userId) {
        return jdbc.queryForObject("select team_member_id from team_members where team_id = ? and user_id = ?", Long.class, teamId, userId);
    }

    private void join(Api.Session leader, Api.Session member, long teamId) throws Exception {
        leader.postJson("/api/teams/{t}/invitations", Map.of("username", member.username), teamId).andExpect(status().isCreated());
        long inv = ((Number) Api.read(member.get("/api/notifications"), "$.items[0].invitationId")).longValue();
        member.post("/api/invitations/{id}/accept", inv).andExpect(status().isNoContent());
    }

    /**
     * [QA6-06] 같은 계정을 동시에 두 번 삭제하면 둘 다 200 이었습니다 — 삭제가 대상 사용자 행을 잠그지 않고 읽어, 두 번째 요청도 커밋되기 전의
     * 사용자를 보고 진행했습니다. 보안 로그도 커밋 전에 찍혀 "삭제됨"이 두 번(롤백돼도) 남았습니다.
     */
    @Test
    @DisplayName("[QA6-06] 같은 계정을 동시에 두 번 삭제하면 두 번째는 404 이고, 보안 로그는 커밋된 삭제 한 번만 남는다")
    void concurrentDeleteOfSameAccount(CapturedOutput output) throws Exception {
        Api.Session victim = api().signUp("qa606");
        long admin = adminId();

        Throwable second;
        try (TransactionRace race = new TransactionRace(tx)) {
            second = race.run(() -> {
                adminService.deleteAccount(victim.userId, admin);
                users.flush();
            }, () -> adminService.deleteAccount(victim.userId, admin));
        }

        assertThat(second).isInstanceOf(ApiException.class).hasMessageContaining("계정");
        assertThat(jdbc.queryForObject("select count(*) from users where user_id = ?", Integer.class, victim.userId)).isZero();
        String line = "account-deleted-by-admin admin=" + admin + " user=" + victim.userId;
        assertThat(output.getOut().split(java.util.regex.Pattern.quote(line), -1).length - 1).isEqualTo(1);
    }

    /**
     * [QA6-06] 삭제는 개인 폴더를 먼저 지우고(X 잠금) 커밋 때 사용자 행을 지우는데, 그 계정의 업로드는 사용자 행을 먼저 잠그고(저장 한도)
     * 폴더를 참조합니다(외래 키 S 잠금). 잠금 순서가 반대라 MySQL 교착(1213)이 나 한쪽이 503 이었습니다(6차 QA 블랙박스에서 업로드 503).
     */
    @Test
    @DisplayName("[QA6-06] 관리자 삭제와 그 계정의 업로드가 겹쳐도 교착(1213)이 나지 않는다 — 삭제가 사용자 행부터 잠가 차례로 처리")
    void deletionDoesNotDeadlockWithUpload() throws Exception {
        Api.Session victim = api().signUp("qa606u");
        long root = victim.rootFolderId;
        long admin = adminId();
        TransactionTemplate readCommitted = new TransactionTemplate(txManager);
        readCommitted.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 업로드의 3단계 흉내: 사용자 행 잠금(저장 한도) → 그 사람의 최상위 폴더를 참조하는 행 INSERT(외래 키 S 잠금)
            Future<Throwable> upload = pool.submit(() -> {
                try {
                    readCommitted.executeWithoutResult(st -> {
                        users.lockById(victim.userId);
                        locked.countDown();
                        await(proceed);
                        jdbc.update("insert into folders (name, owner_id, parent_folder_id, created_at) values ('늦은 폴더', ?, ?, ?)",
                                victim.userId, root, Timestamp.from(Instant.now()));
                    });
                    return null;
                } catch (Throwable e) {
                    return e;
                }
            });
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> deletion = pool.submit(() -> {
                try {
                    adminService.deleteAccount(victim.userId, admin);
                    return null;
                } catch (Throwable e) {
                    return e;
                }
            });
            Thread.sleep(1500);   // 삭제가 폴더를 지우고 사용자 행에서 기다릴 때까지(수정 전) — 수정 뒤에는 첫 잠금에서 기다림
            proceed.countDown();
            Throwable u = upload.get(30, TimeUnit.SECONDS);
            Throwable d = deletion.get(30, TimeUnit.SECONDS);

            assertThat(u).as("업로드 쪽").isNotInstanceOf(CannotAcquireLockException.class);
            assertThat(d).as("삭제 쪽").isNotInstanceOf(CannotAcquireLockException.class);
            assertThat(d).as("삭제는 업로드가 끝난 뒤 그 폴더까지 지우고 성공").isNull();
            assertThat(jdbc.queryForObject("select count(*) from folders where owner_id = ?", Integer.class, victim.userId)).isZero();
        } finally {
            proceed.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * [QA6-05] 관리자 삭제가 "그 사람이 팀장인 팀" 목록을 팀을 잠그기 전에 읽고, 잠근 뒤에도 팀장이 바뀌었는지 다시 보지 않았습니다.
     * 그 사이 위임이 커밋되면 새 팀장(C)의 팀장 표시를 그대로 둔 채 다른 멤버(B)를 또 팀장으로 올려, 팀장 표시가 둘인 팀이 남았습니다.
     */
    @Test
    @DisplayName("[QA6-05] 관리자 삭제와 팀장 위임이 겹쳐도 팀장은 한 명이다 — 위임받은 팀장을 그대로 두고 다른 멤버를 또 올리던 문제")
    void deletionAndDelegationKeepOneLeader() throws Exception {
        Api.Session leader = api().signUp("qa605a");
        Api.Session first = api().signUp("qa605b");
        Api.Session delegate = api().signUp("qa605c");
        long team = leader.createTeam("qa605-team")[0];
        join(leader, first, team);
        join(leader, delegate, team);
        long admin = adminId();
        long delegateMember = memberId(team, delegate.userId);

        Throwable result;
        try (TransactionRace race = new TransactionRace(tx)) {
            result = race.run(() -> {
                teamService.delegateLeadership(team, delegateMember, leader.userId);
                teams.flush();
            }, () -> adminService.deleteAccount(leader.userId, admin));
        }

        assertThat(result).isNull();
        assertThat(jdbc.queryForObject("select count(*) from team_members where team_id = ? and is_team_leader = 1", Integer.class, team)).isOne();
        assertThat(jdbc.queryForObject("select m.user_id from team_members m join teams t on t.team_id = m.team_id and t.owner_id = m.user_id "
                + "where m.team_id = ? and m.is_team_leader = 1", Long.class, team)).isEqualTo(delegate.userId);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
