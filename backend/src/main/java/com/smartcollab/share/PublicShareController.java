package com.smartcollab.share;

import com.smartcollab.file.FileService;
import com.smartcollab.global.security.ClientIp;
import com.smartcollab.global.util.FileResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Public share", description = "공유 링크로 내려받기 (로그인 불필요)")
@RestController
@RequestMapping("/api/public/shares/{token}")
@RequiredArgsConstructor
public class PublicShareController {

    private final ShareService shareService;
    private final FileService fileService;

    @GetMapping
    public ShareDtos.PublicInfo info(@PathVariable String token) {
        return shareService.info(token);
    }

    @Operation(summary = "비밀번호 확인", description = "5분 동안 유효한 다운로드 허가(grant)를 발급합니다.")
    @PostMapping("/unlock")
    public ShareDtos.UnlockResponse unlock(@PathVariable String token, @RequestBody(required = false) ShareDtos.UnlockRequest body,
                                           HttpServletRequest http) {
        return new ShareDtos.UnlockResponse(shareService.unlock(token, body == null ? null : body.password(),
                ClientIp.of(http)));
    }

    @Operation(summary = "다운로드", description = "비밀번호가 걸린 링크는 unlock 으로 받은 grant 가 필요합니다. 호출마다 다운로드 횟수가 1 차감됩니다.")
    @GetMapping("/download")
    public ResponseEntity<Resource> download(@PathVariable String token, @RequestParam(required = false) String grant) {
        FileService.DownloadTarget target = shareService.consume(token, grant);
        return FileResponses.stream(fileService.open(target), target.size(), target.filename(), false);
    }
}
