# 테스트

| 층 | 도구 | 개수 | 실행 |
|---|---|---:|---|
| 백엔드 단위·통합 | JUnit 6 · Spring Boot Test · MockMvc · **Testcontainers(MySQL 8.4, Azurite)** · ArchUnit | 261 | `cd backend && ./gradlew test` |
| 프론트엔드 단위·컴포넌트 | Vitest · Testing Library · jsdom | 94 | `cd frontend && npm test` |
| E2E (전체 스택) | Playwright · Docker Compose · axe-core | 13 (시나리오 10 + 접근성 2·탭 제목 1) | `docker compose up -d --wait && cd e2e && npx playwright test` |

백엔드 라인 커버리지 **92.0%**, 분기 커버리지 **80.5%** (JaCoCo, `backend/build/reports/jacoco/test/html`).

## 원칙

- **H2 대신 실제 MySQL** 로 통합 테스트합니다. 재귀 CTE·`ON DELETE SET NULL`·조건부 UPDATE 처럼 DB 동작에 기대는 로직이 많아, 운영과 다른 DB 로 테스트하면 통과해도 믿을 수 없기 때문입니다.
- 테스트마다 무작위 사용자 이름을 써서 데이터를 분리하고, 컨테이너와 Spring 컨텍스트는 한 번만 띄웁니다(전체 261건, `./gradlew test` 약 2분 30초). 테스트 JVM 은 운영 컨테이너·CI 와 같은 UTC 로 실행합니다.
- v1 에서 찾은 결함마다 이름에 `[v1 …]` 을, 2026-09 코드 리뷰 항목에는 `[SEC-01]`·`[BUG-02]` 처럼 항목 ID 를 붙인 회귀 테스트가 있습니다 → [REFACTORING_REPORT.md](REFACTORING_REPORT.md), [REVIEW_2026-09.md](REVIEW_2026-09.md)
- 인증은 실제 브라우저처럼 HttpOnly 쿠키 + CSRF 토큰으로 요청합니다(`support/Api`).

## 주요 테스트

| 테스트 | 검증 내용 |
|---|---|
| `AccessControlTest` | v1 의 권한 누락 경로 10종 — 비로그인 파일 열람, 남의 파일·팀·버전·알림 접근, 다른 팀 멤버 조작 |
| `WebSocketSecurityTest` · `StompSubscriptionLimitTest` | 실제 서버(랜덤 포트)에 WebSocket 으로 접속 — 비로그인 거부, 사칭 불가, 비멤버 구독 차단, 알림 푸시, 접속자 갱신, 같은 목적지 중복 구독 거절 / 연결당 구독 상한·해제·끊김 정리, 거절된 구독은 자리를 차지하지 않음 (S-11) |
| `DriveWorkflowTest` | 업로드·다운로드 파일명, 안전한 inline 미리보기, 휴지통 수명주기(커밋 후 저장소 삭제), 폴더 삭제(휴지통·서명 포함), 복사본 다운로드, 순환 이동 차단, 검색 |
| `TextEditingTest` | 낙관적 잠금(409), 버전 복원, 현재 버전 서명·무효화, 추출 요약, 번역 키 없음 처리 |
| `TeamWorkflowTest` | 초대·수락·중복 방지, 권한 적용, 팀장 위임, 팀 삭제 정리, 채팅 커서 페이지, 채팅에 공유된 파일의 정보(미리보기 종류)·휴지통이면 404, 새 멤버의 편집 권한은 초대자를 따름·초대 권한을 잃은 사람의 초대는 수락 불가 (S-08), 팀장인 팀 10개·위임으로도 넘지 않음 (S-10) |
| `ShareLinkTest` | 비밀번호·grant 흐름, **동시 다운로드 12건 중 정확히 3건만 성공**, 만료·해제·휴지통, 만든 사람이 팀에서 나가면 링크 동작 중지 (S-09) |
| `AuthFlowTest` | 쿠키·CSRF 인증 흐름, 요청 제한, 악센트 변형 아이디로 로그인 불가·긴 아이디 조기 거절 (S-02·S-03), 체험 계정 아이디로 가입 불가 (S-19) |
| `FolderLimitsTest` · `FolderConcurrencyTest` · `FileContentConcurrencyTest` | 폴더 깊이 50단계·복사 폴더 1,000개·중복 항목 (S-04·S-05) / 다른 트랜잭션이 잠금을 쥔 채 기다리는 동안 요청을 보내(`support/TransactionRace`) 경합을 결정적으로 재현 — 휴지통 표시 유실·순환 이동·휴지통 폴더 아래 생성 (S-06), 저장·복원과 겹친 서명 (S-17), 복사 중 대상이 깊은 곳으로 옮겨지면 잠근 뒤 깊이 재확인 / `TrashConcurrencyTest`: 휴지통 비우기·자동 비우기와 겹친 파일 복원 (독립 검토) |
| `AccountDeletionTest` | 복잡한 이력이 있는 사용자의 탈퇴와 팀 자료 이관 |
| `QueryCountBenchmarkTest` · `DownloadMemoryBenchmarkTest` · `StorageConnectionBenchmarkTest` | 성능 측정값 산출 ([PERFORMANCE.md](PERFORMANCE.md)). 마지막은 업로드·복사·텍스트 저장·읽기·커밋 뒤 삭제가 저장소 입출력 중 DB 커넥션을 붙잡지 않는지 검증 (PERF-01·P-01~P-03) |
| `CsrfEndpointTest` | 실제 서버에서 CSRF 발급 토큰이 헤더로 쓸 수 있는 값인지, 로그인한 요청이 토큰을 바꾸지 않는지 (BUG-07·08) |
| `ClientIpRateLimitTest` | 실제 Tomcat 에 X-Forwarded-For 를 위조해 보내도 프록시가 덧붙인 IP 로 제한되는지 (SEC-01) |
| `StorageCleanupTest` | 트랜잭션 밖에서 쓴 파일을 DB 저장·후속 복사 실패 시 지우는지 (PERF-01) |
| `SpaRoutingTest` | 화면 경로는 index.html, API·정적 파일은 그대로 (BUG-02) |
| `StorageQuotaTest` | 저장 공간 한도 — 옛 버전·휴지통 포함, 복사·텍스트 저장, 팀 한도, **동시 업로드 8개 중 한도만큼 6개만** (SEC-05) |
| `DemoProtectionTest` | 체험 계정의 탈퇴·팀 삭제·새 팀 만들기 등 차단, 체험 한도, 초기화 후 복원 (SEC-06·S-10). 데모 모드를 켠 별도 컨텍스트 |
| `TrashPurgeScheduleTest` · `CorsProfileTest` · `ProductionSettingsTest` · `GlobalExceptionHandlerTest` | 스케줄 시간대, 프로필별 CORS 출처, 운영 프로필은 DB 환경 변수 없이 기동하지 않음 (S-14), 로그에 입력값을 남기지 않는지 |
| `DependencyVersionTest` | Jackson·Tomcat 이 공지가 고쳐진 버전 아래로 내려가지 않는지 (S-01) |
| `RequestBodyLimitTest` | 실제 Tomcat 에 한도를 넘는 본문을 보내면 Content-Length·chunked 모두 413, 파일 업로드는 제외 (SEC-10), 업로드 경로가 아닌 multipart 는 읽기 전에 415 (S-18) |
| `RequestTraceTest` | 추적 ID 가 응답 헤더·오류 본문·로그에서 같은지, 위조된 ID 교체, 쿼리 문자열·입력한 자격 증명·공유 토큰을 로그에 남기지 않는지 (ARC-02·SEC-11·S-07) |
| `SecurityHeadersTest` · `TranslationServiceTest` | CSP 가 인라인 스크립트·스타일을 막는지 (SEC-12), 파일 응답의 sandbox(PDF 미리보기 제외) (S-13) / 사용자별 하루 번역 분량, 거절된 요청은 분량 미사용, 권한 확인이 먼저 (SEC-07), 호출 실패 시 분량 반환 (S-16) |
| `FolderTrashTest` | 폴더 휴지통 — 안의 폴더·파일이 드라이브·트리·검색·직접 접근·공유 링크에서 모두 사라짐, 복원, 영구 삭제(저장 공간 반환), 상위 폴더가 휴지통이면 최상위로 복원, 휴지통 폴더로는 이동·업로드 불가, 복사 시 휴지통 하위 폴더 제외, 보관 기간 만료, 팀 권한 (UX-06) |
| `ItemDeletionTest` · `TrashPurgeBatchTest` | 여러 항목 삭제가 한 트랜잭션(전부 아니면 전무)·폴더당 알림 1번 (PERF-03), 고른 폴더 안의 항목을 함께 골라도 순서와 상관없이 그 폴더와 함께 휴지통으로 (S-15) / 휴지통 자동 비우기 500개씩 배치 (PERF-05) |
| `NotificationQueryPlanTest` | 알림 최신순 조회가 정렬 없이 인덱스 순서로 읽히는지 실행 계획으로 확인 (PERF-04) |
| `ArchitectureTest` · `ModuleBoundaryTest` | 계층·의존 방향 규칙 7개 — 컨트롤러→리포지토리 금지, 서비스의 서블릿·웹 타입·컨트롤러 의존 금지(A-05), 엔티티 응답 금지, global·storage·event 의 도메인 의존 금지 (ARC-05·A-03) / 모듈 사이 양방향 의존·순환·다른 모듈 리포지토리 사용이 기준선([ADR-0010](adr/0010-module-boundaries-and-events.md))과 같은지 (A-08) |
| `PermissionRulesTest` | AccessPolicy 로 모은 권한 규칙 — 팀 휴지통은 삭제 권한, 서명은 소유자·팀장, 채팅에는 그 팀의 휴지통이 아닌 파일만 (A-06) |
| `AzureBlobStorageTest` | Azure 구현을 에뮬레이터(Azurite)로 검증 — 업로드·서버 측 복사·SAS URL·삭제 |
| `SlidingWindowRateLimiterTest` 외 단위 테스트 | 요청 제한(동시성·키별 창으로 정리·분량 반환), 트리 구성(깊이 5,000), 요약 알고리즘, grant 서명, 파일명 검증(방향 제어 문자, S-12), DeepL 호출 형식 |
| E2E `smoke.spec.ts` | 모든 화면의 CSP 위반 감시(`fixtures.ts`, 하나라도 있으면 실패), CSP 헤더, 문서 편집·버전, 업로드·이름 변경·휴지통 복원, **두 사용자 실시간 채팅·폴더 반영**, 비로그인 공유 다운로드, 검색 화면 새로고침, 로그아웃·비밀번호 오류 뒤 로그인 (BUG-09), 폴더를 휴지통에 넣고 복원 (UX-06), 팀 채팅에 올린 파일 미리보기(이미지가 실제로 그려지는지) |
| 프론트 `upload.test` · `EditorPage.test` · `http.test` | 대기 중 업로드 취소, 업로드의 401·CSRF 처리, 편집기 앱 내 이동 차단, 충돌 해결 시 편집본 보관, 다시 불러오기가 실패해도 편집 내용 유지 (FB-02)·충돌 해결 실패 안내 (FB-08), CSRF 토큰 요청의 네트워크 오류를 한국어로 (FB-12) |
| `TeamMembershipRaceTest` · `UploadLockContentionTest` | 출시 기준 QA 의 경합 — 팀장 위임과 나가기·내보내기가 겹쳐도 팀장 없는 팀이 남지 않음 (QA-01), 같은 초대의 수락·거절은 한쪽만 (QA-05) / 다른 사용자의 저장 한도 확인이 내 업로드를 막지 않음(교착의 원인) (QA-06) |
| `MultipartErrorTest` · `DatabaseFailFastTest` · `ItemRequestValidationTest` | 실제 Tomcat 에서 깨진·중단된 업로드 본문과 NUL 파일 이름은 400 이고 ERROR 로그가 없음 (QA-03) / 닿지 않는 DB 는 5초 안에 포기 (QA-02) / items 의 null 항목은 400 (QA-04) |
| `DeletedAccountTokenTest` · `WebSocketSessionExpiryTest` · `AuthSessionTest` | 탈퇴한 계정의 토큰은 쿠키·Bearer 모두 모든 API 에서 401, 탈퇴가 거절되면 그대로, 탈퇴한 계정의 WebSocket 은 닫음 (QA-07) / 로그인 여부 확인은 로그인 전·만료 쿠키에도 200 (IMP-10) |
| `StorageUsageCounterTest` · `FolderPagingTest` | 한도 확인은 사용량 집계를 쓰고, 업로드·저장·복사·휴지통·영구 삭제·동시 업로드 뒤에도 집계 = 실제 합계, 정리 작업이 어긋난 집계를 바로잡음 (IMP-01) / 폴더 목록을 나눠 주고 커서로 빠짐·겹침 없이 끝까지, 서버 정렬(폴더 우선·자연 정렬), 잘못된 매개변수 400 (IMP-02) |
| `HealthReadinessTest` · `TransactionTimeoutTest` · `OrphanBlobCleanerTest` | readiness 에 DB 포함·liveness 제외, DB 연결 실패는 503 + Retry-After (IMP-04) / 시간 제한을 넘는 쿼리는 DB 에서 취소 (IMP-08) / 유예 시간보다 오래된 고아·임시 파일만 지움 (IMP-05) |
| `AuthorizationMatrixTest` | 출시 기준 QA 의 인가 행렬을 CI 로 — 외부인 42요청·남의 ID 섞기 8요청·읽기 전용 멤버 17요청이 모두 404·403 이고, 주인이 다시 조회해 부수 효과가 없는지 (IMP-09) |
| `EventOriginTest`, 프론트 `teamEvents.test` · `http.test` | 요청의 탭 ID 가 폴더 변경 이벤트에 실리고 형식이 틀리면 버림, 자기 탭의 이벤트는 다시 불러오지 않음, 모든 요청에 탭 ID 헤더 (IMP-03) |
| E2E `a11y.spec.ts` | 로그인·드라이브·팀(내가 보낸 채팅 파일 카드 포함)·선택 작업 바·버전 기록·알림·휴지통을 axe(WCAG 2.1 AA·모범 사례)로 검사 — 위반이 하나라도 있으면 실패, 화면별 탭 제목 (UX-01·02) / 팀 멤버·팀 메뉴·없는 폴더·공유 받기·375 폭, 보이는 글자를 포함하는 이름(실험 규칙) (QA-09~13) |
| 프론트 `AppErrorPage.test` · `useDocumentTitle.test` · `josa.test`, 백엔드 `ApiExceptionTest` | 렌더링 오류·새 배포 안내 (ARC-04), 탭 제목 (UX-02), 받침에 맞는 조사 (UX-04) |
| 프론트 `ChatTab.test` · `TeamActivityProvider.test` · `NotificationBell.test` | 채팅에 공유된 파일을 누르면 지금 파일 정보로 드라이브와 같은 미리보기, 내려받기 링크 분리, 지워진 파일 안내, 보일 때만 보는 중 (FB-06) / 다시 연결되면 채팅·알림 다시 받기 (FB-05) / 알림 요청 실패 안내 (FB-08) |
| 프론트 `TrashPage.test` | 휴지통의 폴더(파일 수)·파일 구분, 폴더 복원(최상위로 옮겨진 경우 안내), 폴더 영구 삭제 확인 (UX-06) |
| 프론트 `FileTable.test` · `RowMenu.test` · `DrivePage.test`(구조 정리 전 동작 고정 6개 포함) | 큰 폴더를 200개씩 그리기·더 보기, 전체 선택은 받은 항목 전부, 키보드로 다음 묶음 이동, 정렬 시 처음부터 (PERF-02), 받은 순서대로 그리고 정렬·다음 묶음은 서버에 요청 (IMP-02), Delete·F2 는 키를 누른 행 기준 (FB-01), 행 안 버튼의 Enter·Space 한 번만 (FB-09) / 다시 그려도 메뉴 초점 유지 (FB-10) / 하위 폴더로 옮겨도 팀 패널 유지 (FB-07)·옮긴 뒤 끝난 삭제는 지운 폴더를 갱신·좁은 화면의 보는 중 표시 (FB-06) |
| 프론트 `ShareDialog.test` · `VersionHistoryDialog.test` · `MoveCopyDialog.test` | 다른 파일로 열면 입력이 남지 않음 (FB-03), 닫은 뒤 끝난 요청도 그 파일의 캐시 갱신·링크 해제 실패 안내 (FB-11), 다시 열면 이전 대상 폴더가 남지 않음 (FB-04) |
| 프론트 순수 함수 `chatMessages.test` · `teamEvents.test` · `validateUpload.test` · `keepDraft.test` | 메시지 묶기·검사, 팀 이벤트·알림의 캐시 반영(React 없이), 업로드 전 검사, 편집본 파일 이름 (5단계) |
| 프론트 `AuthProvider.test` | 실제 App·데이터 라우터로 로그아웃·세션 만료(401)·탈퇴 뒤 로그인 화면에 머무는지, 이전 사용자 캐시 삭제, 편집 중 세션 만료 시 확인, 비밀번호 오류 뒤 로그인 (BUG-09), 세션 만료 안내 (UX-03) |

`.env` 의 `APP_PORT` 를 바꿨다면 E2E 에 주소를 알려 주세요: `E2E_BASE_URL=http://localhost:8081 npx playwright test`.

로컬에서 E2E 를 1분 안에 여러 번 돌리면, 모든 요청이 한 IP 로 묶여 로그인 요청 제한(분당 10회)에 걸립니다. 반복 실행할 때는 `.env` 에 `LOGIN_RATE_PER_MINUTE=200`, `LOGIN_ACCOUNT_RATE=200` 을 넣고 `docker compose up -d --wait` 로 다시 띄우세요(CI 의 E2E 작업도 같은 값을 씁니다).

E2E 가 실제로 잡아낸 결함: 넓은 화면에서도 모바일용 팀 패널이 숨은 채로 함께 렌더링되어 채팅 요청·DOM 이 중복되던 문제(`useMediaQuery` 로 한 쪽만 렌더링하도록 수정).

## 출시 기준 QA 점검 스크립트

CI 와 별도로, QA 스택(`docker compose -f docker-compose.yml -f qa/compose.qa.yml -p sc-qa up -d --wait`, 8080)에 돌리는 점검 스크립트가 [qa/](../qa) 에 있습니다.
- 백엔드·API: 인가 행렬·인증·CSRF·입력 퍼징·파일 처리·경계값·동시 요청
- 신뢰성: 장애 주입(Toxiproxy)·정합성 검사·백업 복원
- 성능: 부하(k6)·Lighthouse
- 화면: 화면 크롤·접근성 확장 점검(`e2e/qa`, `npx playwright test -c playwright.qa.config.ts`)

방법과 결과는 [QA_2026-10-03](QA_2026-10-03.md) 에 있습니다.
