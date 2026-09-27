# 테스트

| 층 | 도구 | 개수 | 실행 |
|---|---|---:|---|
| 백엔드 단위·통합 | JUnit 6 · Spring Boot Test · MockMvc · **Testcontainers(MySQL 8.4, Azurite)** · ArchUnit | 156 | `cd backend && ./gradlew test` |
| 프론트엔드 단위·컴포넌트 | Vitest · Testing Library · jsdom | 33 | `cd frontend && npm test` |
| E2E (전체 스택) | Playwright · Docker Compose | 8 시나리오 | `docker compose up -d --wait && cd e2e && npx playwright test` |

백엔드 라인 커버리지 **89.5%**, 분기 커버리지 **76.3%** (JaCoCo, `backend/build/reports/jacoco/test/html`).

## 원칙

- **H2 대신 실제 MySQL** 로 통합 테스트합니다. 재귀 CTE·`ON DELETE SET NULL`·조건부 UPDATE 처럼 DB 동작에 기대는 로직이 많아, 운영과 다른 DB 로 테스트하면 통과해도 믿을 수 없기 때문입니다.
- 테스트마다 무작위 사용자 이름을 써서 데이터를 분리하고, 컨테이너와 Spring 컨텍스트는 한 번만 띄웁니다(전체 156건 약 1분 20초). 테스트 JVM 은 운영 컨테이너·CI 와 같은 UTC 로 실행합니다.
- v1 에서 찾은 결함마다 이름에 `[v1 …]` 을, 2026-09 코드 리뷰 항목에는 `[SEC-01]`·`[BUG-02]` 처럼 항목 ID 를 붙인 회귀 테스트가 있습니다 → [REFACTORING_REPORT.md](REFACTORING_REPORT.md), [REVIEW_2026-09.md](REVIEW_2026-09.md)
- 인증은 실제 브라우저처럼 HttpOnly 쿠키 + CSRF 토큰으로 요청합니다(`support/Api`).

## 주요 테스트

| 테스트 | 검증 내용 |
|---|---|
| `AccessControlTest` | v1 의 권한 누락 경로 10종 — 비로그인 파일 열람, 남의 파일·팀·버전·알림 접근, 다른 팀 멤버 조작 |
| `WebSocketSecurityTest` | 실제 서버(랜덤 포트)에 WebSocket 으로 접속 — 비로그인 거부, 사칭 불가, 비멤버 구독 차단, 알림 푸시, 접속자 갱신 |
| `DriveWorkflowTest` | 업로드·다운로드 파일명, 안전한 inline 미리보기, 휴지통 수명주기(커밋 후 저장소 삭제), 폴더 삭제(휴지통·서명 포함), 복사본 다운로드, 순환 이동 차단, 검색 |
| `TextEditingTest` | 낙관적 잠금(409), 버전 복원, 현재 버전 서명·무효화, 추출 요약, 번역 키 없음 처리 |
| `TeamWorkflowTest` | 초대·수락·중복 방지, 권한 적용, 팀장 위임, 팀 삭제 정리, 채팅 커서 페이지 |
| `ShareLinkTest` | 비밀번호·grant 흐름, **동시 다운로드 12건 중 정확히 3건만 성공**, 만료·해제·휴지통 |
| `AccountDeletionTest` | 복잡한 이력이 있는 사용자의 탈퇴와 팀 자료 이관 |
| `QueryCountBenchmarkTest` · `DownloadMemoryBenchmarkTest` · `StorageConnectionBenchmarkTest` | 성능 측정값 산출 ([PERFORMANCE.md](PERFORMANCE.md)). 마지막은 저장소 입출력 중 DB 커넥션 점유가 0 인지 검증 |
| `CsrfEndpointTest` | 실제 서버에서 CSRF 발급 토큰이 헤더로 쓸 수 있는 값인지, 로그인한 요청이 토큰을 바꾸지 않는지 (BUG-07·08) |
| `ClientIpRateLimitTest` | 실제 Tomcat 에 X-Forwarded-For 를 위조해 보내도 프록시가 덧붙인 IP 로 제한되는지 (SEC-01) |
| `StorageCleanupTest` | 트랜잭션 밖에서 쓴 파일을 DB 저장·후속 복사 실패 시 지우는지 (PERF-01) |
| `SpaRoutingTest` | 화면 경로는 index.html, API·정적 파일은 그대로 (BUG-02) |
| `StorageQuotaTest` | 저장 공간 한도 — 옛 버전·휴지통 포함, 복사·텍스트 저장, 팀 한도, **동시 업로드 8개 중 한도만큼 6개만** (SEC-05) |
| `DemoProtectionTest` | 체험 계정의 탈퇴·팀 삭제 등 차단, 체험 한도, 초기화 후 복원 (SEC-06). 데모 모드를 켠 별도 컨텍스트 |
| `TrashPurgeScheduleTest` · `CorsProfileTest` · `GlobalExceptionHandlerTest` | 스케줄 시간대, 프로필별 CORS 출처, 로그에 입력값을 남기지 않는지 |
| `RequestBodyLimitTest` | 실제 Tomcat 에 한도를 넘는 본문을 보내면 Content-Length·chunked 모두 413, 파일 업로드는 제외 (SEC-10) |
| `RequestTraceTest` | 추적 ID 가 응답 헤더·오류 본문·로그에서 같은지, 위조된 ID 교체, 쿼리 문자열·입력한 자격 증명을 로그에 남기지 않는지 (ARC-02·SEC-11) |
| `SecurityHeadersTest` · `TranslationServiceTest` | CSP 가 인라인 스크립트·스타일을 막는지 (SEC-12) / 사용자별 하루 번역 분량, 거절된 요청은 분량 미사용, 권한 확인이 먼저 (SEC-07) |
| `ArchitectureTest` | 계층·의존 방향 규칙 5개 — 컨트롤러→리포지토리 금지, 서비스의 서블릿 의존 금지, 엔티티 응답 금지, global·storage 의 도메인 의존 금지 (ARC-05) |
| `AzureBlobStorageTest` | Azure 구현을 에뮬레이터(Azurite)로 검증 — 업로드·서버 측 복사·SAS URL·삭제 |
| `SlidingWindowRateLimiterTest` 외 단위 테스트 | 요청 제한(동시성·만료 정리), 트리 구성(깊이 5,000), 요약 알고리즘, grant 서명, 파일명 검증, DeepL 호출 형식 |
| E2E `smoke.spec.ts` | 모든 화면의 CSP 위반 감시(`fixtures.ts`, 하나라도 있으면 실패), CSP 헤더, 문서 편집·버전, 업로드·이름 변경·휴지통 복원, **두 사용자 실시간 채팅·폴더 반영**, 비로그인 공유 다운로드, 검색 화면 새로고침, 로그아웃·비밀번호 오류 뒤 로그인 (BUG-09) |
| 프론트 `upload.test` · `EditorPage.test` | 대기 중 업로드 취소, 업로드의 401·CSRF 처리, 편집기 앱 내 이동 차단, 충돌 해결 시 편집본 보관 |
| 프론트 `AuthProvider.test` | 실제 App·데이터 라우터로 로그아웃·세션 만료(401)·탈퇴 뒤 로그인 화면에 머무는지, 이전 사용자 캐시 삭제, 편집 중 세션 만료 시 확인, 비밀번호 오류 뒤 로그인 (BUG-09) |

`.env` 의 `APP_PORT` 를 바꿨다면 E2E 에 주소를 알려 주세요: `E2E_BASE_URL=http://localhost:8081 npx playwright test`.

로컬에서 E2E 를 1분 안에 여러 번 돌리면, 모든 요청이 한 IP 로 묶여 로그인 요청 제한(분당 10회)에 걸립니다. 반복 실행할 때는 `.env` 에 `LOGIN_RATE_PER_MINUTE=200`, `LOGIN_ACCOUNT_RATE=200` 을 넣고 `docker compose up -d --wait` 로 다시 띄우세요(CI 의 E2E 작업도 같은 값을 씁니다).

E2E 가 실제로 잡아낸 결함: 넓은 화면에서도 모바일용 팀 패널이 숨은 채로 함께 렌더링되어 채팅 요청·DOM 이 중복되던 문제(`useMediaQuery` 로 한 쪽만 렌더링하도록 수정).
