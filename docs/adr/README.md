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
| [0008](0008-extractive-summary.md) | 모의(Mock) 요약 대신 단어 빈도 기반 추출 요약, 번역은 키가 있을 때만 | 채택 (AI 키가 없을 때의 경로, 0011) |
| [0009](0009-row-locks-for-structural-changes.md) | 짧은 구조 변경(폴더 구조·문서 버전과 서명)은 저장 공간·파일 행 잠금으로 차례로 처리 | 채택 |
| [0010](0010-module-boundaries-and-events.md) | 모듈 사이 연쇄 정리는 동기 도메인 이벤트로, 끊는 비용이 큰 순환은 기준선으로 고정 | 채택 |
| [0011](0011-claude-document-tools.md) | 문서 요약·번역에 Claude(Haiku 5.5) — 토큰 예산·결과 캐시·문서는 신뢰하지 않는 입력, 키가 없으면 추출 요약·DeepL | 채택 |
| [0012](0012-ai-billing-and-concurrency.md) | AI 비용은 최악값(입력 + max_tokens) 예약 → 과금된 실패는 한도에 남김, 요약·번역 동시 실행은 사용자당 2·서버 8 | 채택 |
| [0013](0013-access-code-gate.md) | 공개 데모는 로그인 앞에 접근 코드 잠금 — 앱 안의 필터, 다른 포트폴리오 서비스와 같은 scrypt 해시·쿠키·시도 제한 | 채택 |
| [0014](0014-platform-threads-on-java21.md) | Java 21 에서는 요청 처리에 가상 스레드를 쓰지 않음 — CPU 1개(App Service B1)에서 Azure SDK 의 synchronized 대기로 서버 전체가 멈춤 | 채택 |
| [0015](0015-admin-only-account.md) | 관리 전용 계정 — 환경 변수로 만들고 계정 목록·삭제만, 파일·대화에는 닿지 않음. 팀장 계정은 가장 먼저 들어온 멤버에게 위임 | 채택 |
| [0016](0016-pdf-translation-from-extracted-text.md) | PDF 번역은 서버가 뽑은 글자를 보냄(Apache PDFBox) — 원문을 알아 결과를 확인, 글자가 거의 없는 PDF 만 원본, 압축 폭탄 상한 | 채택 |
| [0017](0017-gate-telegram-notify.md) | 접근 코드 잠금 알림은 텔레그램으로 — 잠금 해제(같은 IP 1시간에 한 번)·잠금이 걸린 순간만, 가린 IP, 요청과 떼어 작업자 하나로 보냄 | 채택 |
| [0018](0018-team-and-account-limits.md) | 계정 수 20개(체험 포함)·한 계정의 팀 소속 6개 상한 — 가입은 서버 안 잠금으로 줄 세우고 체험 3자리는 늘 남김 | 채택 |
