package com.smartcollab.global.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 브라우저 주소창으로 직접 들어온 화면 경로(새로고침·딥링크)를 SPA 진입점으로 보냅니다.
 * v1 은 URL 라우팅이 없어 새로고침하면 항상 첫 화면으로 돌아갔습니다.
 * <p>화면 경로는 프론트엔드 라우터가 정하므로 서버에 따로 나열하지 않고 규칙으로 판단합니다:
 * 첫 경로 조각이 서버 경로(api·ws·actuator·v3·swagger-ui·assets·error)가 아니고, 파일 이름처럼 점(.)을 포함하지 않으면 화면 경로입니다.
 * 처음에는 경로를 일일이 나열해, 목록에서 빠진 /search 를 새로고침하면 404 가 났습니다 [BUG-02].</p>
 */
@Controller
public class SpaController {

    private static final String CLIENT_ROUTE =
            "/{segment:(?!(?:api|ws|actuator|v3|swagger-ui|assets|error)$)[^.]+}/**";

    @GetMapping({"/", CLIENT_ROUTE})
    public String index() {
        return "forward:/index.html";
    }
}
