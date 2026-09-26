# 아키텍처 결정 기록 (ADR)

| 번호 | 결정 | 상태 |
|---|---|---|
| [0001](0001-bundled-spa-served-by-spring.md) | 브라우저 내 Babel 변환 대신 Vite 번들 SPA 를 Spring Boot 가 같은 출처로 제공 | 채택 |
| [0002](0002-blob-storage-strategy.md) | 파일 저장소를 전략 패턴으로 추상화하고 DB 트랜잭션과 수명주기를 맞춤 | 채택 |
| [0003](0003-cookie-jwt-and-csrf.md) | JWT 를 HttpOnly 쿠키로 전달하고 CSRF 토큰을 요구 | 채택 |
| [0004](0004-flyway-schema.md) | `ddl-auto: update` 대신 Flyway 마이그레이션 + `validate` | 채택 |
| [0005](0005-central-access-policy.md) | 권한 판단을 AccessPolicy 한 곳으로 모으고 접근 불가 대상은 404 | 채택 |
| [0006](0006-optimistic-concurrency-for-edits.md) | 텍스트 편집 동시성은 낙관적 잠금으로 처리 | 채택 |
| [0007](0007-after-commit-realtime-events.md) | 실시간 알림은 트랜잭션 커밋 후 "무엇이 바뀌었는지"만 보냄 | 채택 |
| [0008](0008-extractive-summary.md) | 모의(Mock) 요약 대신 단어 빈도 기반 추출 요약, 번역은 키가 있을 때만 | 채택 |
