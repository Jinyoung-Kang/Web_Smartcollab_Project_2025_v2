package com.smartcollab.share;

import com.smartcollab.global.security.SlidingWindowRateLimiter;
import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ShareLinkTest extends IntegrationTest {

    @Autowired
    SlidingWindowRateLimiter rateLimiter;

    private String createLink(Api.Session s, long fileId, Map<String, Object> options) throws Exception {
        return Api.read(s.postJson("/api/files/{id}/share-links", options, fileId), "$.token");
    }

    @Test
    @DisplayName("비밀번호 링크: 정보 조회 → 허가 없이 다운로드 403 → 틀린 비밀번호 403 → 허가 발급 → 다운로드")
    void passwordProtectedFlow() throws Exception {
        Api.Session s = api().signUp("share");
        long file = s.uploadText(s.rootFolderId, "공유 문서_최종.txt", "shared content");
        String token = createLink(s, file, Map.of("password", "pa55word", "expiresInHours", 24));

        api().perform(MockMvcRequestBuilders.get("/api/public/shares/{t}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileName").value("공유 문서_최종.txt"))
                .andExpect(jsonPath("$.passwordProtected").value(true));
        api().perform(MockMvcRequestBuilders.get("/api/public/shares/{t}/download", token)).andExpect(status().isForbidden());
        api().perform(MockMvcRequestBuilders.post("/api/public/shares/{t}/unlock", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"nope\"}"))
                .andExpect(status().isForbidden());

        String grant = Api.read(api().perform(MockMvcRequestBuilders.post("/api/public/shares/{t}/unlock", token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"pa55word\"}")), "$.grant");
        // v1 은 공유 다운로드 파일명에서 첫 '_' 앞부분을 잘라냈습니다 ("공유 문서_최종.txt" → "최종.txt")
        api().perform(MockMvcRequestBuilders.get("/api/public/shares/{t}/download", token).param("grant", grant))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("%EA%B3%B5%EC%9C%A0%20%EB%AC%B8%EC%84%9C_%EC%B5%9C%EC%A2%85.txt")))
                .andExpect(content().string("shared content"));
    }

    @Test
    @DisplayName("[S-09] 팀에서 나간 사람이 만든 공유 링크는 동작을 멈추고, 다시 팀에 들어오면 다시 동작한다(링크는 지우지 않음)")
    void linksStopWorkingWhenCreatorLosesAccess() throws Exception {
        Api.Session leader = api().signUp("shl");
        Api.Session member = api().signUp("shm");
        long[] team = leader.createTeam("공유 팀");
        long memberId = joinTeam(leader, member, team[0]);
        long file = member.uploadText(team[1], "팀 문서.md", "v1");
        String token = com.jayway.jsonpath.JsonPath.read(Api.body(member.postJson("/api/files/{id}/share-links", Map.of(), file)), "$.token");
        api().perform(MockMvcRequestBuilders.get("/api/public/shares/{t}", token)).andExpect(status().isOk());

        leader.delete("/api/teams/{t}/members/{m}", team[0], memberId).andExpect(status().isNoContent());
        api().perform(MockMvcRequestBuilders.get("/api/public/shares/{t}", token)).andExpect(status().isGone());
        api().perform(MockMvcRequestBuilders.get("/api/public/shares/{t}/download", token)).andExpect(status().isGone());

        joinTeam(leader, member, team[0]);
        api().perform(MockMvcRequestBuilders.get("/api/public/shares/{t}", token)).andExpect(status().isOk());
    }

    private long joinTeam(Api.Session leader, Api.Session member, long teamId) throws Exception {
        leader.postJson("/api/teams/{t}/invitations", Map.of("username", member.username), teamId).andExpect(status().isCreated());
        long invitation = ((Number) Api.read(member.get("/api/notifications"), "$.items[0].invitationId")).longValue();
        member.post("/api/invitations/{id}/accept", invitation).andExpect(status().isNoContent());
        java.util.List<Number> ids = Api.read(leader.get("/api/teams/{t}", teamId), "$.members[?(@.username == '" + member.username + "')].memberId");
        return ids.getFirst().longValue();
    }

    @Test
    @DisplayName("[BUG-01] 72바이트를 넘는 공유 비밀번호는 500 이 아니라 400")
    void sharePasswordOver72BytesIsRejected() throws Exception {
        Api.Session s = api().signUp("sharelong");
        long file = s.uploadText(s.rootFolderId, "long-pw.txt", "x");
        s.postJson("/api/files/{id}/share-links", Map.of("password", "비밀번호".repeat(7)), file)   // 28자, 84바이트
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").value(containsString("72바이트")));
    }

    @Test
    @DisplayName("[SEC-01] 한 링크의 비밀번호 시도가 한도를 넘으면 IP 가 달라도 429")
    void unlockIsRateLimitedPerLink() throws Exception {
        Api.Session s = api().signUp("sharelim");
        long file = s.uploadText(s.rootFolderId, "limited-link.txt", "x");
        String token = createLink(s, file, Map.of("password", "pa55word"));
        while (rateLimiter.tryAcquire("share-link:" + token, 1000, Duration.ofMinutes(10))) {
            // 테스트 설정의 링크 단위 한도(10분 1000회)를 채움
        }
        api().perform(MockMvcRequestBuilders.post("/api/public/shares/{t}/unlock", token)
                        .with(r -> {
                            r.setRemoteAddr("10.40.50." + (int) (Math.random() * 200));
                            return r;
                        })
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"pa55word\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("[v1 경쟁 조건] 다운로드 횟수 제한은 동시 요청에서도 정확히 지켜진다")
    void downloadLimitIsAtomic() throws Exception {
        Api.Session s = api().signUp("limit");
        long file = s.uploadText(s.rootFolderId, "limited.txt", "x");
        String token = createLink(s, file, Map.of("downloadLimit", 3));

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            Callable<Integer> call = () -> api().perform(MockMvcRequestBuilders.get("/api/public/shares/{t}/download", token))
                    .andReturn().getResponse().getStatus();
            results.add(pool.submit(call));
        }
        int ok = 0;
        for (Future<Integer> f : results) {
            if (f.get() == 200) ok++;
        }
        pool.shutdown();
        assertThat(ok).isEqualTo(3);
        assertThat(jdbc.queryForObject("select download_count from share_links where token = ?", Integer.class, token)).isEqualTo(3);
        api().perform(MockMvcRequestBuilders.get("/api/public/shares/{t}", token)).andExpect(status().isGone());
    }

    @Test
    @DisplayName("휴지통으로 간 파일의 링크는 더 이상 쓸 수 없다 (410)")
    void trashedFileLinkIsGone() throws Exception {
        Api.Session s = api().signUp("gone");
        long file = s.uploadText(s.rootFolderId, "a.txt", "x");
        String token = createLink(s, file, Map.of());
        s.delete("/api/files/{id}", file);
        api().perform(MockMvcRequestBuilders.get("/api/public/shares/{t}/download", token)).andExpect(status().isGone());
    }

    @Test
    @DisplayName("만료된 링크는 410, 링크 목록·해제(revoke)")
    void expiryAndRevoke() throws Exception {
        Api.Session s = api().signUp("expire");
        long file = s.uploadText(s.rootFolderId, "a.txt", "x");
        String token = createLink(s, file, Map.of("expiresInHours", 1));
        jdbc.update("update share_links set expires_at = UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE where token = ?", token);
        api().perform(MockMvcRequestBuilders.get("/api/public/shares/{t}", token)).andExpect(status().isGone());

        String token2 = createLink(s, file, Map.of());
        long linkId = ((Number) Api.read(s.get("/api/files/{id}/share-links", file), "$[0].id")).longValue();
        s.get("/api/files/{id}/share-links", file).andExpect(jsonPath("$[0].token").value(token2))
                .andExpect(jsonPath("$[1].active").value(false));
        s.delete("/api/share-links/{id}", linkId).andExpect(status().isNoContent());
        api().perform(MockMvcRequestBuilders.get("/api/public/shares/{t}", token2)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("팀 파일 공유는 올린 사람 또는 팀장만")
    void teamFileShareRestricted() throws Exception {
        Api.Session leader = api().signUp("sharel");
        Api.Session member = api().signUp("sharem");
        long[] team = leader.createTeam("공유 팀");
        leader.postJson("/api/teams/{t}/invitations", Map.of("username", member.username), team[0]);
        long inv = ((Number) Api.read(member.get("/api/notifications"), "$.items[0].invitationId")).longValue();
        member.post("/api/invitations/{id}/accept", inv);
        long leadersFile = leader.uploadText(team[1], "팀 문서.txt", "x");
        member.postJson("/api/files/{id}/share-links", Map.of(), leadersFile).andExpect(status().isForbidden());
        long membersFile = member.uploadText(team[1], "내가 올림.txt", "x");
        member.postJson("/api/files/{id}/share-links", Map.of(), membersFile).andExpect(status().isCreated());
        leader.postJson("/api/files/{id}/share-links", Map.of(), membersFile).andExpect(status().isCreated());
    }
}
