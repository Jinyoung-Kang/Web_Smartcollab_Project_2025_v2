package com.smartcollab.support;

import org.springframework.test.context.TestPropertySource;

/**
 * 저장 한도·데모 모드를 작게 켠 통합 테스트 공통 설정 (같은 설정이면 Spring 컨텍스트를 공유합니다).
 * 데모 데이터(체험 팀 약 13KB, 개인 1KB 미만 — 로컬 측정)가 데모 한도 안에 들어가도록 정했습니다.
 */
@TestPropertySource(properties = {
        "app.quota.personal=64KB",
        "app.quota.team=128KB",
        "app.demo.enabled=true",
        "app.demo.password=" + LimitedStorageIntegrationTest.DEMO_PASSWORD,
        "app.demo.quota=32KB",
        "app.demo.sample-files=false",   // 파일 유형별 표본(약 190KB)은 이 작은 한도에 들어가지 않음 — DemoSampleFilesTest 에서 확인
})
public abstract class LimitedStorageIntegrationTest extends IntegrationTest {

    public static final String DEMO_PASSWORD = "demo-pass-1";
}
