package com.smartcollab.security;

import org.apache.catalina.util.ServerInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 알려진 취약점이 고쳐진 버전 아래로 내려가지 않게 고정합니다. Spring Boot 가 관리하는 버전이 따라잡으면
 * build.gradle.kts 의 버전 지정을 지워도 이 테스트는 계속 통과해야 합니다.
 */
class DependencyVersionTest {

    @Test
    @DisplayName("[S-01] Jackson 3 은 3.1.7 이상 — GHSA-7hhh-6rmp-j9qf 등 파서·역직렬화 DoS 수정 버전")
    void jackson3() {
        assertThat(number(tools.jackson.databind.cfg.PackageVersion.VERSION.toString())).isGreaterThanOrEqualTo(3_001_007);
        assertThat(number(tools.jackson.core.json.PackageVersion.VERSION.toString())).isGreaterThanOrEqualTo(3_001_007);
    }

    @Test
    @DisplayName("[S-01] Jackson 2(springdoc·Azure SDK 가 사용)는 2.22.3 이상")
    void jackson2() {
        assertThat(number(com.fasterxml.jackson.databind.cfg.PackageVersion.VERSION.toString())).isGreaterThanOrEqualTo(2_022_003);
        assertThat(number(com.fasterxml.jackson.core.json.PackageVersion.VERSION.toString())).isGreaterThanOrEqualTo(2_022_003);
    }

    @Test
    @DisplayName("[SEC-03] Tomcat 은 11.0.25 이상 — CVE-2026-65905·65182·68525 수정 버전")
    void tomcat() {
        assertThat(number(ServerInfo.getServerNumber())).isGreaterThanOrEqualTo(11_000_025);
    }

    /** "3.1.7", "3.1.7-SNAPSHOT", "11.0.26.0" → 3_001_007 처럼 주·부·수 버전만 비교 (Jackson Version.compareTo 는 그룹 이름부터 비교해 쓸 수 없음) */
    static int number(String version) {
        String[] v = version.split("[.-]");
        return Integer.parseInt(v[0]) * 1_000_000 + Integer.parseInt(v[1]) * 1_000 + Integer.parseInt(v[2]);
    }
}
