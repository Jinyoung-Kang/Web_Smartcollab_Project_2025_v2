package com.smartcollab.global.gate;

import org.springframework.web.util.HtmlUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 잠금화면 HTML [GATE-01]. 모양과 문구 구성은 포트폴리오의 다른 서비스 잠금화면과 같습니다.
 * <p>인라인 스크립트·스타일이 없습니다 — 스타일은 {@code /__gate/gate.css} 로 따로 받고, 잠금화면의 CSP 는 {@code style-src 'self'} 입니다.</p>
 */
final class GatePage {

    static final String TITLE = "SmartCollab";
    static final List<String> DESCRIPTION = List.of("팀용 클라우드 파일 협업", "드라이브 · 권한 · 버전·서명 · 팀 채팅 · 외부 공유");
    static final List<String> INTRO = List.of("포트폴리오 데모입니다.", "접근 코드는 포트폴리오 문서에 있습니다.");
    static final List<String> DEMO_NOTE = List.of("잠금을 푼 뒤 로그인 화면의 체험 계정으로 둘러볼 수 있습니다.", "체험 데이터는 매일 초기화됩니다.");
    private static final String ITEM_SEPARATOR = " · ";

    private final String hours;
    private final boolean demoEnabled;

    GatePage(String hours, boolean demoEnabled) {
        this.hours = hours;
        this.demoEnabled = demoEnabled;
    }

    String render(String next, String error) {
        boolean hasError = error != null && !error.isBlank();
        StringBuilder html = new StringBuilder(2048);
        html.append("""
                <!doctype html>
                <html lang="ko">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <meta name="robots" content="noindex, nofollow">
                """);
        html.append("<title>").append(escape(TITLE)).append(" · 잠금</title>\n");
        html.append("<link rel=\"stylesheet\" href=\"").append(AccessGateFilter.PREFIX).append("/gate.css\">\n");
        html.append("</head>\n<body>\n<main>\n");
        html.append("  <p class=\"lock\">🔒 접근 코드가 필요합니다</p>\n");
        html.append("  <h1>").append(escape(TITLE)).append("</h1>\n");
        html.append("  <p class=\"desc\">").append(lines(DESCRIPTION)).append("</p>\n");
        html.append("  <form method=\"post\" action=\"").append(AccessGateFilter.PREFIX).append("/unlock\">\n");
        html.append("    <input type=\"hidden\" name=\"next\" value=\"").append(escape(next)).append("\">\n");
        html.append("    <label for=\"code\">접근 코드</label>\n");
        html.append("    <input id=\"code\" name=\"code\" type=\"password\" autocomplete=\"off\" autocapitalize=\"off\"")
                .append(" spellcheck=\"false\" maxlength=\"").append(AccessCodes.MAX_CODE_CHARS).append("\" required autofocus")
                .append(hasError ? " aria-invalid=\"true\" aria-describedby=\"gate-error\"" : "").append(">\n");
        html.append("    <button type=\"submit\">잠금 해제</button>\n");
        if (hasError) {
            html.append("    <p id=\"gate-error\" class=\"error\" role=\"alert\">").append(lines(List.of(error))).append("</p>\n");
        }
        html.append("  </form>\n  <div class=\"meta\">\n");
        html.append("    <p>").append(lines(INTRO)).append("</p>\n");
        if (!hours.isEmpty()) {
            html.append("    <p>운영 시간: <span class=\"u\">").append(escape(hours)).append("</span></p>\n");
        }
        if (demoEnabled) {
            html.append("    <p>").append(lines(DEMO_NOTE)).append("</p>\n");
        }
        html.append("  </div>\n</main>\n</body>\n</html>\n");
        return html.toString();
    }

    /** 문장마다 새 줄, ' · ' 로 나뉜 항목은 줄 중간에서 끊지 않고 구분점은 앞 항목에 붙입니다(줄이 '·' 로 시작하지 않게). */
    private static String lines(List<String> sentences) {
        return sentences.stream().map(String::strip).filter(s -> !s.isEmpty()).map(line -> {
            String[] items = line.split(ITEM_SEPARATOR);
            if (items.length == 1) {
                return "<span class=\"ln\">" + escape(line) + "</span>";
            }
            StringBuilder inner = new StringBuilder();
            for (int i = 0; i < items.length; i++) {
                inner.append(i == 0 ? "" : " ").append("<span class=\"u\">").append(escape(items[i]))
                        .append(i == items.length - 1 ? "" : " ·").append("</span>");
            }
            return "<span class=\"ln\">" + inner + "</span>";
        }).collect(Collectors.joining());
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value, "UTF-8");
    }
}
