package com.smartcollab.ai;

import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** [M-1] 요약·번역의 동시 실행 상한 — 하루 한도가 넉넉해도 한 사용자가 겹쳐 보낸 요청이 그만큼 문서를 읽고 과금하지 못하게 */
@TestPropertySource(properties = "app.ai.tokens-per-user-per-day=1000000")
class DocumentToolsConcurrencyTest extends IntegrationTest {

    @MockitoBean
    DocumentAi documentAi;

    @Test
    @DisplayName("[M-1] 한 사용자가 동시에 돌릴 수 있는 요약·번역은 2건 — 3번째는 바로 429")
    void perUserConcurrencyIsBounded() throws Exception {
        when(documentAi.enabled()).thenReturn(true);
        when(documentAi.model()).thenReturn("claude-haiku-5-5");
        when(documentAi.countSummaryTokens(any())).thenReturn(100L);
        Api.Session s = api().signUp("m1conc");
        long[] files = new long[3];
        for (int i = 0; i < 3; i++) files[i] = s.uploadText(s.rootFolderId, "f" + i + ".txt", "문서 " + i);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(documentAi.summarize(any())).thenAnswer(inv -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return new DocumentAi.Output<>(new DocumentAi.Summary("요약", List.of()), 100, 10);
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> running = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                long id = files[i];
                running.add(pool.submit(() -> s.send(MockMvcRequestBuilders.post("/api/files/{id}/summary", id)).andReturn().getResponse().getStatus()));
            }
            assertThat(entered.await(10, TimeUnit.SECONDS)).as("두 요청이 AI 호출까지 들어감").isTrue();

            s.send(MockMvcRequestBuilders.post("/api/files/{id}/summary", files[2]))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
            release.countDown();
            for (Future<Integer> f : running) assertThat(f.get()).isEqualTo(200);

            s.send(MockMvcRequestBuilders.post("/api/files/{id}/summary", files[2])).andExpect(status().isOk());   // 끝나면 다시 됨
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
