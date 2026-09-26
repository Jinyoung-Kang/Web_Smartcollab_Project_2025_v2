package com.smartcollab.global.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 브라우저 주소창으로 직접 들어온 화면 경로(새로고침·딥링크)를 SPA 진입점으로 보냅니다.
 * v1 은 URL 라우팅이 없어 새로고침하면 항상 첫 화면으로 돌아갔습니다.
 */
@Controller
public class SpaController {

    @GetMapping({"/", "/login", "/signup", "/drive", "/drive/**", "/teams/**", "/trash", "/trash/**",
            "/share/**", "/files/**"})
    public String index() {
        return "forward:/index.html";
    }
}
