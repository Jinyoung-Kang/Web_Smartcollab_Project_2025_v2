package com.smartcollab.auth;

import com.smartcollab.global.config.AppProperties;
import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import com.smartcollab.user.DemoAccounts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

/**
 * [CAP-03] 가입할 수 있는 계정은 체험 계정을 포함해 20개(관리·시스템 계정 제외). 같은 DB 에 다른 시험의 계정이 쌓여 있으므로, 상한을
 * "지금 계정 수 + n" 으로 바꿔 시험합니다. 체험 모드에서는 체험 계정 3자리를 늘 남겨 둡니다 — 매일 초기화는 체험 계정을 지웠다가 다시
 * 만드는데, 그 사이 일반 가입이 자리를 차지하면 체험 계정을 다시 만들지 못합니다.
 */
class AccountLimitTest extends IntegrationTest {

    @MockitoSpyBean
    AppProperties props;
    @Autowired
    AuthService authService;

    private int userAccounts() {
        return jdbc.queryForObject("select count(*) from users where role = 'USER'", Integer.class);
    }

    private int reservedDemoAccounts() {
        String names = String.join(",", DemoAccounts.ACCOUNTS.stream().map(a -> "'" + a.username() + "'").toList());
        return jdbc.queryForObject("select count(*) from users where role = 'USER' and username in (" + names + ")", Integer.class);
    }

    private void limitTo(int accounts, boolean demo) {
        AppProperties.Quota q = props.quota();
        doReturn(new AppProperties.Quota(q.personal(), q.team(), q.teamsPerUser(), accounts)).when(props).quota();
        AppProperties.Demo d = props.demo();
        doReturn(new AppProperties.Demo(demo, d.password(), d.quota(), d.sampleFiles())).when(props).demo();
    }

    private int signUp(String prefix) throws Exception {
        String username = prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return mvc.perform(MockMvcRequestBuilders.post("/api/auth/signup").with(Api.xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", Api.PASSWORD,
                                "passwordConfirm", Api.PASSWORD, "name", "가입"))))
                .andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("[CAP-03] 계정 수가 상한에 닿으면 가입은 409 — 체험 계정은 세고, 관리·시스템 계정은 세지 않음")
    void signUpStopsAtLimit() throws Exception {
        limitTo(userAccounts() + 1, false);

        assertThat(signUp("cap03a")).isEqualTo(201);
        assertThat(signUp("cap03b")).isEqualTo(409);
        String body = mvc.perform(MockMvcRequestBuilders.post("/api/auth/signup").with(Api.xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", "cap03c_x", "password", Api.PASSWORD,
                                "passwordConfirm", Api.PASSWORD, "name", "가입"))))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("가입할 수 있는 계정 수");
    }

    @Test
    @DisplayName("[CAP-03] 동시에 가입해도 상한을 넘지 않는다 — 8건이 겹쳐도 남은 2자리만 가입")
    void concurrentSignUpsStayWithinLimit() throws Exception {
        int limit = userAccounts() + 2;
        limitTo(limit, false);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                int n = i;
                results.add(pool.submit(() -> signUp("cap03r" + n)));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get());
            }
            assertThat(statuses).filteredOn(s -> s == 201).hasSize(2);
            assertThat(statuses).filteredOn(s -> s == 409).hasSize(6);
        } finally {
            pool.shutdownNow();
        }
        assertThat(userAccounts()).isEqualTo(limit);
    }

    @Test
    @DisplayName("[CAP-03] 체험 모드면 체험 계정 3자리를 늘 남겨 둔다(초기화로 잠시 지워져도). 체험 데이터 생성(내부 가입)은 상한과 무관")
    void demoSlotsAreReserved() throws Exception {
        int regular = userAccounts() - reservedDemoAccounts();
        limitTo(regular + DemoAccounts.ACCOUNTS.size() + 1, true);

        assertThat(signUp("cap03d")).isEqualTo(201);
        assertThat(signUp("cap03e")).isEqualTo(409);
        // 체험 데이터 생성은 서버 안에서 직접 부르는 가입이라 막지 않습니다(그 3자리는 위에서 남겨 둠)
        String internal = "cap03i_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        assertThat(authService.signUp(new AuthDtos.SignUpRequest(internal, Api.PASSWORD, Api.PASSWORD, "내부", null)).getId()).isPositive();
    }
}
