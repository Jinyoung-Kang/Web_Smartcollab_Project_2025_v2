package com.smartcollab.global.security;

import jakarta.servlet.http.HttpServletRequest;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 요청 제한에 쓰는 클라이언트 IP.
 * <p>프록시 헤더(X-Forwarded-For)는 Tomcat 의 RemoteIpValve({@code server.forward-headers-strategy: native})가
 * 오른쪽(프록시가 덧붙인 값)부터 읽어 신뢰할 수 있는 프록시를 건너뛴 첫 주소를 {@code getRemoteAddr()} 로 바꿔 둡니다.
 * 클라이언트가 헤더 앞쪽에 넣은 값은 쓰이지 않습니다.</p>
 * <p>일부 프록시는 "1.2.3.4:5678" 처럼 포트를 붙여 보내는데, 포트는 연결마다 달라 그대로 키로 쓰면 제한이 풀립니다.
 * 그래서 포트를 떼어 IP 만 남깁니다.</p>
 */
public final class ClientIp {

    private static final Pattern BRACKETED_IPV6 = Pattern.compile("^\\[([^]]+)](?::\\d+)?$");
    private static final Pattern IPV4_WITH_PORT = Pattern.compile("^(\\d{1,3}(?:\\.\\d{1,3}){3}):\\d+$");

    private ClientIp() {
    }

    public static String of(HttpServletRequest request) {
        return normalize(request.getRemoteAddr());
    }

    static String normalize(String address) {
        if (address == null || address.isBlank()) {
            return "unknown";
        }
        String value = address.strip();
        Matcher ipv6 = BRACKETED_IPV6.matcher(value);
        if (ipv6.matches()) {
            return ipv6.group(1);
        }
        Matcher ipv4 = IPV4_WITH_PORT.matcher(value);
        return ipv4.matches() ? ipv4.group(1) : value;
    }
}
