package com.smartcollab.file;

import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [QA-04] 여러 항목 이동·복사·삭제 요청의 items 에 null 항목이 있으면 NullPointerException 으로 500 과 ERROR 로그가 났습니다
 * (입력 퍼징, qa/results/api-fuzz.md). 다른 잘못된 항목처럼 400 으로 거절해야 합니다.
 */
class ItemRequestValidationTest extends IntegrationTest {

    @ParameterizedTest(name = "[QA-04] {0} 의 items 에 null 이 있으면 400")
    @ValueSource(strings = {"/api/items/move", "/api/items/copy", "/api/items/delete"})
    void nullItemIsRejected(String path) throws Exception {
        Api.Session s = api().signUp("qa04");
        long folder = s.createFolder(s.rootFolderId, "대상");
        String body = "{\"items\":[null],\"targetFolderId\":" + folder + "}";

        s.send(MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
