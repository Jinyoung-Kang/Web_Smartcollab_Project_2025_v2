package com.smartcollab.file;

import com.smartcollab.global.config.AppProperties;
import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * [CAP-01] 기본 저장 한도. 개인 드라이브는 1GB, 체험 계정은 50MB 였는데 둘 다 2GB 로 늘립니다(사용자 요청, Azure 비용 검토 뒤).
 * 한도는 계정마다 저장하지 않고 설정에서 읽으므로 새로 만드는 계정과 이미 있는 계정에 함께 적용됩니다. 팀 한도(5GB)는 그대로입니다.
 */
class DefaultQuotaTest extends IntegrationTest {

    private static final long GB = 1024L * 1024 * 1024;

    @Autowired
    AppProperties props;

    @Test
    @DisplayName("[CAP-01] 기본 저장 한도는 개인 드라이브·체험 계정 2GB, 팀 5GB — 새로 가입한 계정의 사용량 응답도 2GB")
    void defaultQuotas() throws Exception {
        assertThat(props.quota().personal()).isEqualTo(DataSize.ofGigabytes(2));
        assertThat(props.demo().quota()).isEqualTo(DataSize.ofGigabytes(2));
        assertThat(props.quota().team()).isEqualTo(DataSize.ofGigabytes(5));

        Api.Session s = api().signUp("cap01");
        s.get("/api/files/usage").andExpect(jsonPath("$.quotaBytes").value(2 * GB));
    }
}
