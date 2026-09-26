package com.smartcollab.system;

import com.smartcollab.ai.DeepLTranslationClient;
import com.smartcollab.global.config.AppProperties;
import com.smartcollab.storage.BlobStorage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 화면이 서버 설정에 맞춰 기능을 켜고 끌 수 있도록 공개 설정을 알려 줍니다.
 * (예: 번역 키가 없으면 번역 버튼을 비활성화하고 이유를 표시)
 */
@Tag(name = "Public config")
@RestController
@RequiredArgsConstructor
public class PublicConfigController {

    private final DeepLTranslationClient translator;
    private final BlobStorage storage;
    private final AppProperties props;

    @Value("${spring.servlet.multipart.max-file-size}")
    private DataSize maxUpload;

    @Operation(summary = "공개 설정", description = "기능 사용 가능 여부, 업로드 한도, 데모 계정(데모 모드일 때만)")
    @GetMapping("/api/public/config")
    public PublicConfig config() {
        Demo demo = props.demo().enabled()
                ? new Demo(true, DemoDataSeeder.ACCOUNTS.stream().map(a -> new DemoAccount(a.username(), a.name(), a.role())).toList(),
                props.demo().password())
                : new Demo(false, List.of(), null);
        return new PublicConfig(translator.enabled(), "azure".equals(storage.type()), maxUpload.toBytes(), demo);
    }

    public record PublicConfig(boolean translationEnabled, boolean officePreviewEnabled, long maxUploadBytes, Demo demo) {
    }

    public record Demo(boolean enabled, List<DemoAccount> accounts, String password) {
    }

    public record DemoAccount(String username, String name, String role) {
    }
}
