package com.smartcollab.global.error;

import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [QA-08] 상태 코드만 아는 곳(라우팅 전 거절·/error)의 오류 본문. 상태마다 고르는 코드와, 알려지지 않은 상태·추적 ID 가 없는 경우를 확인합니다.
 */
class ProblemWriterTest extends IntegrationTest {

    @Test
    @DisplayName("[QA-08] 상태 코드에 맞는 오류 코드를 고른다 (정하지 않은 4xx 는 INVALID_REQUEST, 5xx 는 INTERNAL_ERROR)")
    void codeForStatus() {
        Map<Integer, ErrorCode> expected = Map.ofEntries(
                Map.entry(400, ErrorCode.INVALID_REQUEST), Map.entry(401, ErrorCode.UNAUTHORIZED), Map.entry(403, ErrorCode.FORBIDDEN),
                Map.entry(404, ErrorCode.NOT_FOUND), Map.entry(405, ErrorCode.INVALID_REQUEST), Map.entry(409, ErrorCode.CONFLICT),
                Map.entry(413, ErrorCode.PAYLOAD_TOO_LARGE), Map.entry(415, ErrorCode.UNSUPPORTED_MEDIA_TYPE),
                Map.entry(429, ErrorCode.RATE_LIMITED), Map.entry(500, ErrorCode.INTERNAL_ERROR), Map.entry(502, ErrorCode.INTERNAL_ERROR),
                Map.entry(503, ErrorCode.SERVICE_UNAVAILABLE));
        expected.forEach((status, code) -> assertThat(ErrorCode.forStatus(status)).as("상태 %d", status).isEqualTo(code));
    }

    @Test
    @DisplayName("[QA-08] 400 은 주소·형식 안내와 추적 ID, 알려지지 않은 상태는 그 상태 그대로 problem+json")
    void writesStatusProblem() throws Exception {
        MockHttpServletResponse bad = new MockHttpServletResponse();
        ProblemWriter.writeStatus(bad, json, 400, "trace-12345678");
        JsonNode badBody = json.readTree(bad.getContentAsString());
        assertThat(bad.getStatus()).isEqualTo(400);
        assertThat(bad.getContentType()).startsWith("application/problem+json");
        assertThat(badBody.path("code").asString()).isEqualTo("INVALID_REQUEST");
        assertThat(badBody.path("detail").asString()).isEqualTo("요청 주소나 형식이 올바르지 않습니다.");
        assertThat(badBody.path("requestId").asString()).isEqualTo("trace-12345678");

        MockHttpServletResponse unknown = new MockHttpServletResponse();
        ProblemWriter.writeStatus(unknown, json, 499, null);
        JsonNode unknownBody = json.readTree(unknown.getContentAsString());
        assertThat(unknown.getStatus()).isEqualTo(499);
        assertThat(unknownBody.path("status").asInt()).isEqualTo(499);
        assertThat(unknownBody.path("title").asString()).isEqualTo("Error");
        assertThat(unknownBody.path("code").asString()).isEqualTo("INVALID_REQUEST");
        assertThat(unknownBody.has("requestId")).isFalse();
    }
}
