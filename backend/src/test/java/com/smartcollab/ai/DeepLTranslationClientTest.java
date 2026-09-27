package com.smartcollab.ai;

import com.smartcollab.global.config.AppProperties;
import com.smartcollab.global.error.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DeepLTranslationClientTest {

    private static AppProperties props(String key) {
        return new AppProperties(null, null, null, null, null, null, null, new AppProperties.Deepl(key, "https://deepl.test"), null, null);
    }

    @Test
    @DisplayName("줄 경계를 지키며 나누고, 긴 한 줄은 강제로 자른다")
    void chunking() {
        assertThat(DeepLTranslationClient.chunkByLines("a\nb\nc", 3)).containsExactly("a\nb", "c");
        assertThat(DeepLTranslationClient.chunkByLines("abcdefg", 3)).containsExactly("abc", "def", "g");
        List<String> chunks = DeepLTranslationClient.chunkByLines("줄1\n\n줄3", 100);
        assertThat(String.join("\n", chunks)).isEqualTo("줄1\n\n줄3");
    }

    @Test
    @DisplayName("헤더 인증(DeepL-Auth-Key)으로 호출하고 응답을 이어 붙인다")
    void callsApiWithHeaderAuth() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://deepl.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://deepl.test/v2/translate"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "DeepL-Auth-Key secret-key"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"target_lang\":\"EN-US\"")))
                .andRespond(withSuccess("{\"translations\":[{\"detected_source_language\":\"KO\",\"text\":\"Hello\"}]}",
                        MediaType.APPLICATION_JSON));
        DeepLTranslationClient client = new DeepLTranslationClient(props("secret-key"), builder.build());
        DeepLTranslationClient.Translation t = client.translate("안녕하세요", "EN-US");
        assertThat(t.text()).isEqualTo("Hello");
        assertThat(t.detectedSourceLanguage()).isEqualTo("KO");
        server.verify();
    }

    @Test
    @DisplayName("키가 없으면 흉내 내지 않고 FEATURE_DISABLED")
    void disabledWithoutKey() {
        DeepLTranslationClient client = new DeepLTranslationClient(props(""), RestClient.builder().build());
        assertThat(client.enabled()).isFalse();
        assertThatThrownBy(() -> client.translate("x", "EN-US")).isInstanceOf(ApiException.class)
                .hasMessageContaining("DEEPL_API_KEY");
    }
}
