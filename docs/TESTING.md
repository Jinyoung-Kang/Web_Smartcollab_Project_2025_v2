# 테스트

| 층 | 도구 | 개수 | 실행 |
|---|---|---:|---|
| 백엔드 단위·통합 | JUnit 6 · Spring Boot Test · MockMvc · **Testcontainers(MySQL 8.4, Azurite)** · ArchUnit | 395 | `cd backend && ./gradlew test` |
| 백엔드 — CPU 1개 JVM | 같은 도구, `-XX:ActiveProcessorCount=1`(운영 App Service B1 과 같은 조건) · HTTPS Azurite | 2 | `cd backend && ./gradlew singleCarrierTest` (`check` 에 포함, CI 백엔드 작업에서도 실행) |
| 프론트엔드 단위·컴포넌트 | Vitest · Testing Library · jsdom | 182 | `cd frontend && npm test` |
| E2E (전체 스택) | Playwright · Docker Compose · axe-core | 19 (시나리오 10 + 관리자 1 + 대화상자 초점 2 + 배치 2 + 접근성 3·탭 제목 1) | `docker compose up -d --wait && cd e2e && npx playwright test` |

백엔드 라인 커버리지 **92.3%**, 분기 커버리지 **79.9%** (JaCoCo, `backend/build/reports/jacoco/test/html`).

## 원칙

- **H2 대신 실제 MySQL** 로 통합 테스트합니다. 재귀 CTE·`ON DELETE SET NULL`·조건부 UPDATE 처럼 DB 동작에 기대는 로직이 많아, 운영과 다른 DB 로 테스트하면 통과해도 믿을 수 없기 때문입니다.
- 테스트마다 무작위 사용자 이름을 써서 데이터를 분리하고, 컨테이너와 Spring 컨텍스트는 한 번만 띄웁니다(전체 395건, `./gradlew clean build` 는 CPU 1개 시험·커버리지 포함 4분 33초 — 관리 전용 계정 추가 뒤 측정). 테스트 JVM 은 운영 컨테이너·CI 와 같은 UTC 로 실행합니다.
- v1 에서 찾은 결함마다 이름에 `[v1 …]` 을, 2026-09 코드 리뷰 항목에는 `[SEC-01]`·`[BUG-02]` 처럼 항목 ID 를 붙인 회귀 테스트가 있습니다 → [REFACTORING_REPORT.md](REFACTORING_REPORT.md), [REVIEW_2026-09.md](REVIEW_2026-09.md)
- 인증은 실제 브라우저처럼 HttpOnly 쿠키 + CSRF 토큰으로 요청합니다(`support/Api`). CSRF 는 Spring Security 의 `csrf()` 도우미 대신 같은 토큰을 쿠키·헤더로 보내는 `Api.xsrf()` 를 씁니다 — `csrf()` 는 공유 CSRF 필터의 저장소를 바꿔, 같은 컨텍스트의 실제 HTTP 시험이 실행 순서에 따라 실패했습니다.

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
| `SingleCarrierDownloadTest` (`singleCarrierTest` 작업) | CPU 1개 JVM + HTTPS Azurite 에서 32MB 업로드·내려받기 동안 상태 확인이 응답하고, 커밋 뒤 저장소 삭제가 끝남 — 가상 스레드를 켜면 서버 전체가 멈추던 문제 (C-1, ADR-0014) |
| `DeletionUploadRaceTest` · `TeamDeletionInvitationRaceTest` | 팀 삭제와 겹친 업로드·초대 수락이 FK 오류·교착 없이 끝남, 업로드와 겹친 탈퇴는 409 로 깨끗이 거절(알려진 한계) (L-6) |
| `QueryCountBenchmarkTest` · `DownloadMemoryBenchmarkTest` · `StorageConnectionBenchmarkTest` | 성능 측정값 산출 ([PERFORMANCE.md](PERFORMANCE.md)). 마지막은 업로드·복사·텍스트 저장·읽기·커밋 뒤 삭제가 저장소 입출력 중 DB 커넥션을 붙잡지 않는지 검증 (PERF-01·P-01~P-03) |
| `CsrfEndpointTest` | 실제 서버에서 CSRF 발급 토큰이 헤더로 쓸 수 있는 값인지, 로그인한 요청이 토큰을 바꾸지 않는지 (BUG-07·08) |
| `ClientIpRateLimitTest` | 실제 Tomcat 에 X-Forwarded-For 를 위조해 보내도 프록시가 덧붙인 IP 로 제한되는지 (SEC-01) |
| `StorageCleanupTest` | 트랜잭션 밖에서 쓴 파일을 DB 저장·후속 복사 실패 시 지우는지 (PERF-01) |
| `SpaRoutingTest` | 화면 경로는 index.html, API·정적 파일은 그대로 (BUG-02) |
| `StorageQuotaTest` | 저장 공간 한도 — 옛 버전·휴지통 포함, 복사·텍스트 저장, 팀 한도, **동시 업로드 8개 중 한도만큼 6개만** (SEC-05) |
| `DemoProtectionTest` | 체험 계정의 탈퇴·팀 삭제·새 팀 만들기 등 차단, 체험 한도, 초기화 후 복원 (SEC-06·S-10), 관리자도 체험 계정은 삭제 불가·목록에 체험 계정과 체험 한도로 표시 (ADM-11). 데모 모드를 켠 별도 컨텍스트 |
| `TrashPurgeScheduleTest` · `CorsProfileTest` · `ProductionSettingsTest` · `GlobalExceptionHandlerTest` | 스케줄 시간대, 프로필별 CORS 출처, 운영 프로필은 DB 환경 변수 없이 기동하지 않음 (S-14), 로그에 입력값을 남기지 않는지 |
| `DependencyVersionTest` | Jackson·Tomcat 이 공지가 고쳐진 버전 아래로 내려가지 않는지 (S-01) |
| `DatabaseNetworkFailureTest` | DB 앞 중계기로 네트워크 장애 재현 — 쿼리를 보낸 뒤 연결이 소리 없이 끊기면 쿼리 시간 제한만으로는 풀리지 않음(원인), 앱 설정의 소켓 읽기 상한(트랜잭션 시간 제한보다 길게)과 그 상한에서의 실패 (DEP-01), 기동 때 DB 가 3초 동안 닿지 않아도 Flyway 가 다시 시도 (DEP-02). 원자료 `qa/results/deploy-2026-10-12` |
| `AzureBlobStorageTest` · `FileNamesTest` | Office 미리보기 URL 의 응답이 문서 형식(Content-Type)을 다는지 — 저장 키에 확장자가 없어 Office 웹 뷰어가 "파일을 찾을 수 없음"을 내던 문제, 확장자별 형식 (DEP-03). 원자료 `qa/results/deploy-2026-10-12/office-preview.txt` |
| `RequestBodyLimitTest` | 실제 Tomcat 에 한도를 넘는 본문을 보내면 Content-Length·chunked 모두 413, 파일 업로드는 제외 (SEC-10), 업로드 경로가 아닌 multipart 는 읽기 전에 415 (S-18) |
| `RequestTraceTest` | 추적 ID 가 응답 헤더·오류 본문·로그에서 같은지, 위조된 ID 교체, 쿼리 문자열·입력한 자격 증명·공유 토큰을 로그에 남기지 않는지 (ARC-02·SEC-11·S-07) |
| `ScryptTest` · `AccessGateFilterTest` · `AccessGateIntegrationTest`, 프론트 `http.test` | 접근 코드 잠금 — 직접 구현한 scrypt 가 RFC 7914 벡터·Python `hashlib.scrypt`(다른 서비스 gate 의 해시)와 같은지, 잠금화면(인라인 스타일 없음)·API 401 `GATE_LOCKED`·헬스 체크 예외, 쿠키(__Host-·HttpOnly·Secure·SameSite) 위조·만료·코드 폐기, IP·전체 시도 제한, 다른 출처 거절·열린 리디렉션 방지, 실제 Tomcat 에서 보안 필터보다 먼저, 잠금이 풀린 화면에서 쿠키가 만료되면 잠금화면으로 (GATE-01) |
| `SecurityHeadersTest` · `TranslationServiceTest` | CSP 가 인라인 스크립트·스타일을 막는지 (SEC-12), 파일 응답의 sandbox(PDF 미리보기 제외) (S-13) / 사용자별 하루 번역 분량, 거절된 요청은 분량 미사용, 권한 확인이 먼저 (SEC-07), 호출 실패 시 분량 반환 (S-16) |
| `FolderTrashTest` | 폴더 휴지통 — 안의 폴더·파일이 드라이브·트리·검색·직접 접근·공유 링크에서 모두 사라짐, 복원, 영구 삭제(저장 공간 반환), 상위 폴더가 휴지통이면 최상위로 복원, 휴지통 폴더로는 이동·업로드 불가, 복사 시 휴지통 하위 폴더 제외, 보관 기간 만료, 팀 권한 (UX-06) |
| `ItemDeletionTest` · `TrashPurgeBatchTest` | 여러 항목 삭제가 한 트랜잭션(전부 아니면 전무)·폴더당 알림 1번 (PERF-03), 고른 폴더 안의 항목을 함께 골라도 순서와 상관없이 그 폴더와 함께 휴지통으로 (S-15) / 휴지통 자동 비우기 500개씩 배치 (PERF-05) |
| `NotificationQueryPlanTest` | 알림 최신순 조회가 정렬 없이 인덱스 순서로 읽히는지 실행 계획으로 확인 (PERF-04) |
| `ArchitectureTest` · `ModuleBoundaryTest` | 계층·의존 방향 규칙 7개 — 컨트롤러→리포지토리 금지, 서비스의 서블릿·웹 타입·컨트롤러 의존 금지(A-05), 엔티티 응답 금지, global·storage·event 의 도메인 의존 금지 (ARC-05·A-03) / 모듈 사이 양방향 의존·순환·다른 모듈 리포지토리 사용이 기준선([ADR-0010](adr/0010-module-boundaries-and-events.md))과 같은지 (A-08) |
| `PermissionRulesTest` | AccessPolicy 로 모은 권한 규칙 — 팀 휴지통은 삭제 권한, 서명은 소유자·팀장, 채팅에는 그 팀의 휴지통이 아닌 파일만 (A-06) |
| `ClaudeDocumentAiTest` · `DocumentToolsTest` · `DocxTextTest` | Claude 요청 형식을 가짜 Anthropic 서버(`FakeAnthropicServer`)로 — 모델·effort low·번역 스트리밍·요약 구조화 출력·document 블록(텍스트·PDF)·시스템 프롬프트의 "신뢰하지 않는 문서", 거절 422·출력 잘림 413·429→503·401→502, 실측에서 나온 결과 정리(번역을 감싼 문서 표기·코드 울타리 벗기기, 요약의 원문에 없는 연도 떼기, 번역 notes → translation 구조화 출력·작업별 effort, 번역에 파일 이름 안 보냄, 한국어 결과의 일본어 조사 고치기) (AI-06~11) / AI 를 가짜로 바꾼 통합 시험 — 키가 있으면 생성형·없으면 추출 요약·DeepL, 권한이 먼저(볼 수 없으면 404 이고 AI 를 부르지 않음), 텍스트·PDF·Word 만, 입력 토큰 상한 413, 하루 토큰 한도 예약·정산·실패 시 반환, 버전별 결과 캐시 / Word 본문 추출·XXE 거절·압축 폭탄 상한 (AI-01~05) |
| `AzureBlobStorageTest` | Azure 구현을 에뮬레이터(Azurite)로 검증 — 업로드·서버 측 복사·SAS URL·삭제 |
| `SlidingWindowRateLimiterTest` 외 단위 테스트 | 요청 제한(동시성·키별 창으로 정리·분량 반환), 트리 구성(깊이 5,000), 요약 알고리즘, grant 서명, 파일명 검증(방향 제어 문자, S-12), DeepL 호출 형식 |
| E2E `smoke.spec.ts` | 모든 화면의 CSP 위반 감시(`fixtures.ts`, 하나라도 있으면 실패), CSP 헤더, 문서 편집·버전, 업로드·이름 변경·휴지통 복원, **두 사용자 실시간 채팅·폴더 반영**, 비로그인 공유 다운로드, 검색 화면 새로고침, 로그아웃·비밀번호 오류 뒤 로그인 (BUG-09), 폴더를 휴지통에 넣고 복원 (UX-06), 팀 채팅에 올린 파일 미리보기(이미지가 실제로 그려지는지) |
| 프론트 `upload.test` · `EditorPage.test` · `http.test` | 대기 중 업로드 취소, 업로드의 401·CSRF 처리, 편집기 앱 내 이동 차단, 충돌 해결 시 편집본 보관, 다시 불러오기가 실패해도 편집 내용 유지 (FB-02)·충돌 해결 실패 안내 (FB-08), CSRF 토큰 요청의 네트워크 오류를 한국어로 (FB-12) |
| `TeamMembershipRaceTest` · `UploadLockContentionTest` | 출시 기준 QA 의 경합 — 팀장 위임과 나가기·내보내기가 겹쳐도 팀장 없는 팀이 남지 않음 (QA-01), 같은 초대의 수락·거절은 한쪽만 (QA-05) / 다른 사용자의 저장 한도 확인이 내 업로드를 막지 않음(교착의 원인) (QA-06) |
| `MultipartErrorTest` · `DatabaseFailFastTest` · `ItemRequestValidationTest` | 실제 Tomcat 에서 깨진·중단된 업로드 본문과 NUL 파일 이름은 400 이고 ERROR 로그가 없음 (QA-03) / 닿지 않는 DB 는 5초 안에 포기 (QA-02) / items 의 null 항목은 400 (QA-04) |
| `DeletedAccountTokenTest` · `WebSocketSessionExpiryTest` · `AuthSessionTest` | 탈퇴한 계정의 토큰은 쿠키·Bearer 모두 모든 API 에서 401, 탈퇴가 거절되면 그대로, 탈퇴한 계정의 WebSocket 은 닫음 (QA-07) / 로그인 여부 확인은 로그인 전·만료 쿠키에도 200 (IMP-10) |
| `StorageUsageCounterTest` · `FolderPagingTest` | 한도 확인은 사용량 집계를 쓰고, 업로드·저장·복사·휴지통·영구 삭제·동시 업로드 뒤에도 집계 = 실제 합계, 정리 작업이 어긋난 집계를 바로잡음 (IMP-01) / 폴더 목록을 나눠 주고 커서로 빠짐·겹침 없이 끝까지, 서버 정렬(폴더 우선·자연 정렬), 잘못된 매개변수 400 (IMP-02) |
| `HealthReadinessTest` · `TransactionTimeoutTest` · `OrphanBlobCleanerTest` | readiness 에 DB 포함·liveness 제외, DB 연결 실패는 503 + Retry-After (IMP-04) / 시간 제한을 넘는 쿼리는 DB 에서 취소 (IMP-08) / 유예 시간보다 오래된 고아·임시 파일만 지움 (IMP-05) |
| `AuthorizationMatrixTest` | 출시 기준 QA 의 인가 행렬을 CI 로 — 외부인 42요청·남의 ID 섞기 8요청·읽기 전용 멤버 17요청이 모두 404·403 이고, 주인이 다시 조회해 부수 효과가 없는지 (IMP-09) / 관리 전용 계정의 남의 파일·폴더·팀·채팅 요청 14건과 일반 사용자의 관리 API 요청 3건이 모두 403 (ADM-02) |
| `AdminAccountTest` · `AdminAccountInitializerTest` | 관리 전용 계정(ADR-0015) — 역할 ADMIN·드라이브 없음, 드라이브·팀·채팅·알림·검색·탈퇴 API 403, 일반 사용자는 관리 API 403·로그인 전 401, 목록은 아이디·이름·소속 팀(팀장 여부)·사용량·한도·가입일만이고 시스템·관리자 계정은 없음, 삭제하면 개인 파일(저장소 포함)이 지워지고 팀에서 빠지며 토큰 거절·보안 로그, 팀장 계정은 가장 먼저 들어온 멤버에게 위임·알림하고 혼자인 팀은 삭제, 시스템·관리자·없는 ID 404·잘못된 쪽 400, 관리자는 초대 불가, 설정과 다른 아이디의 예전 관리자는 로그인 불가 (ADM-01~09) / 아이디가 없으면 꺼짐, 12자 미만 비밀번호·체험 아이디는 기동 거절, 해시만 저장, 같은 아이디의 일반 계정은 올리지 않음, 비밀번호를 바꾸면 다음 기동 때 반영 (ADM-10) |
| `PreRoutingErrorFormatTest` · `ProblemWriterTest` | 실제 Tomcat 에서 라우팅 전에 거절된 주소(`%00`·`%2F`)와 방화벽이 거절한 주소(`//`·`;`)도 problem+json·추적 ID, `/error` 를 직접 부르면 404, 상태별 오류 코드 (QA-08) |
| `EventOriginTest`, 프론트 `teamEvents.test` · `http.test` | 요청의 탭 ID 가 폴더 변경 이벤트에 실리고 형식이 틀리면 버림, 자기 탭의 이벤트는 다시 불러오지 않음, 모든 요청에 탭 ID 헤더 (IMP-03) |
| E2E `a11y.spec.ts` | 로그인·드라이브·팀(내가 보낸 채팅 파일 카드 포함)·선택 작업 바·버전 기록·알림·휴지통을 axe(WCAG 2.1 AA·모범 사례)로 검사 — 위반이 하나라도 있으면 실패, 화면별 탭 제목 (UX-01·02) / 팀 멤버·팀 메뉴·없는 폴더·공유 받기·375 폭, 보이는 글자를 포함하는 이름(실험 규칙) (QA-09~13) |
| E2E `admin.spec.ts`, 프론트 `AdminPage.test` · `App.test` | 관리자로 로그인하면 관리 화면(드라이브 주소로 와도), 계정 목록의 소속 팀·사용량, 접근성 위반 0·375 폭 가로 넘침 없음, 아이디를 입력해야 삭제, 삭제된 계정의 열린 화면은 로그인 화면으로·다시 로그인 불가 (ADM-12·13). `ADMIN_USERNAME`·`ADMIN_PASSWORD` 가 없으면 E2E 는 건너뜀 |
| E2E `layout.spec.ts` | 주소가 긴 운영 도메인(긴 `*.localhost` 하위 도메인으로 흉내)에서 공유 대화상자의 만든 링크 줄과 복사·해제 단추가 대화상자 안에 들어가는지 (DEP-04) / 띄어쓰기 없는 긴 이름(50자)·팀 이름(100자)이 사이드바·헤더(로고·계정 단추·계정 메뉴)·팀 멤버 목록(관리 단추)·채팅 보낸 사람·권한 변경 대화상자 밖으로 넘치지 않고, 375 폭에서 로고 아이콘이 눌리지 않는지 (UX-07) |
| 프론트 `AppErrorPage.test` · `useDocumentTitle.test` · `josa.test`, 백엔드 `ApiExceptionTest` | 렌더링 오류·새 배포 안내 (ARC-04), 탭 제목 (UX-02), 받침에 맞는 조사 (UX-04) |
| 프론트 `ChatTab.test` · `TeamActivityProvider.test` · `NotificationBell.test` | 채팅에 공유된 파일을 누르면 지금 파일 정보로 드라이브와 같은 미리보기, 내려받기 링크 분리, 지워진 파일 안내, 보일 때만 보는 중 (FB-06) / 다시 연결되면 채팅·알림 다시 받기 (FB-05) / 알림 요청 실패 안내 (FB-08) |
| 프론트 `TrashPage.test` | 휴지통의 폴더(파일 수)·파일 구분, 폴더 복원(최상위로 옮겨진 경우 안내), 폴더 영구 삭제 확인 (UX-06) |
| 프론트 `FileTable.test` · `RowMenu.test` · `DrivePage.test`(구조 정리 전 동작 고정 6개 포함) | 큰 폴더를 200개씩 그리기·더 보기, 전체 선택은 받은 항목 전부, 키보드로 다음 묶음 이동, 정렬 시 처음부터 (PERF-02), 받은 순서대로 그리고 정렬·다음 묶음은 서버에 요청 (IMP-02), Delete·F2 는 키를 누른 행 기준 (FB-01), 행 안 버튼의 Enter·Space 한 번만 (FB-09) / 다시 그려도 메뉴 초점 유지 (FB-10) / 하위 폴더로 옮겨도 팀 패널 유지 (FB-07)·옮긴 뒤 끝난 삭제는 지운 폴더를 갱신·좁은 화면의 보는 중 표시 (FB-06) |
| 프론트 `ShareDialog.test` · `VersionHistoryDialog.test` · `MoveCopyDialog.test` | 다른 파일로 열면 입력이 남지 않음 (FB-03), 닫은 뒤 끝난 요청도 그 파일의 캐시 갱신·링크 해제 실패 안내 (FB-11), 다시 열면 이전 대상 폴더가 남지 않음 (FB-04) |
| 프론트 순수 함수 `chatMessages.test` · `teamEvents.test` · `validateUpload.test` · `keepDraft.test` | 메시지 묶기·검사, 팀 이벤트·알림의 캐시 반영(React 없이), 업로드 전 검사, 편집본 파일 이름 (5단계) |
| 프론트 `EditorPage.test` · `PreviewDialog.test` (요약·번역) | AI 요약(요약 문단·핵심 항목·모델·Anthropic 전송 안내)·번역 언어 선택, 키가 없으면 핵심 문장·번역 꺼짐 / 미리보기의 PDF 요약·Word 번역, 이미지에는 도구 없음, AI 키가 없을 때 PDF 는 꺼지고 이유 안내, DeepL 만 있으면 텍스트 번역 (AI-01·02) |
| 프론트 `AuthProvider.test` | 실제 App·데이터 라우터로 로그아웃·세션 만료(401)·탈퇴 뒤 로그인 화면에 머무는지, 이전 사용자 캐시 삭제, 편집 중 세션 만료 시 확인, 비밀번호 오류 뒤 로그인 (BUG-09), 세션 만료 안내 (UX-03) |
| `DocxTextTest` · `ExtractiveSummarizerTest` · `DocumentToolsTest`(H-1) | 수 KB Word 압축 폭탄(앞쪽 항목에 숨긴 것 포함)이 힙을 다 쓰지 않고 413, 뽑은 본문이 편집기 한도를 넘으면 키가 있든 없든 413, 추출 요약을 상위 k 개 방식으로 바꾼 뒤에도 고르는 문장이 같음(동작 고정) — 측정은 `qa/scripts/probes` ([REVIEW_2026-10-12](REVIEW_2026-10-12.md)) |
| `ClaudeDocumentAiTest` · `DocumentToolsTest`(H-2) · `DocumentToolsConcurrencyTest` · `DocumentToolSlotsTest` · `PdfRequestAllocationBenchmarkTest` | 응답을 받은 뒤의 실패(잘림·거절·형식 오류·스트림 끊김)는 과금 토큰을 한도에 남김, 번역 예약은 요청의 max_tokens 와 같음, 번역 스트림은 바이트가 계속 오면 `AI_TIMEOUT` 보다 오래 걸려도 받고 멈추면 끊되 과금으로 봄 (H-2) / 한도를 다 쓰면 문서를 읽기 전에 429, 사용자당 2·서버 전체 동시 실행 상한, PDF 요청 메모리 할당 상한 (M-1) |
| `CommitOutcomeUnknownTest` · `TransactionRunnerTest` | 커밋은 됐는데 확인 응답을 못 받은 업로드·텍스트 저장·복사가 저장소 파일을 지우지 않음 — 실제 커밋 뒤 예외를 던지는 트랜잭션 관리자로 재현 (H-4) |
| `DemoProtectionTest` · `PasswordCheckConnectionTest` · `ShareLinkTest` · `AzureBlobStorageTest` | 체험 계정에게 팀장 위임 불가 (H-3), 초기화가 중간에 실패해 일부만 남은 체험 계정도 다음 시드가 정리 (L-3) / 로그인·공유 비밀번호 확인 중 DB 커넥션을 잡지 않음 (M-2) / 공유 다운로드는 저장소를 연 뒤에만 횟수 차감 (L-2) / Azure 복사의 404·실패 상태 (L-1) |
| `ArchitectureTest`(L-7) | STOMP 컨트롤러도 리포지토리 금지, `List<엔티티>`·`ResponseEntity<엔티티>` 응답 금지, `@Service` 밖 도메인 클래스의 들어오는 HTTP 타입 금지 — 일부러 넣은 위반 3종으로 규칙이 잡는지 확인 |
| `DemoSampleFilesTest` | 체험 계정 셋 모두 내 드라이브에 파일 유형별 표본 16개(미리보기 종류별)가 있고, 초기화 뒤에도 다시 생김. 체험 데이터는 DB 에 하나뿐이라 체험 계정을 쓰는 시험은 시작할 때 자기 설정으로 다시 만듦 |
| `BulkDeletionBenchmarkTest` (`BENCH=true` 일 때만) | 파일 5천·2만·5만 개 계정 삭제 시간 (M-6, 결과 `qa/results/review-2026-10-12`) |
| 프론트 `useTextDocument.test` · `Dialog.test` · `DeleteAccountDialog.test` · `Menu.test` · `SearchPage.test` · `endpoints.test` 외 (L-8) | 응답을 기다리는 사이 입력한 글을 덮지 않음, 저장·덮어쓰기는 한 번에 하나·사용량 캐시도 갱신, 대화상자 안에서 끌다 밖에서 놓아도 닫히지 않음, 탈퇴 창 다시 열면 비밀번호 비움, 메뉴·좁은 화면 패널 Esc·초점 복귀, 검색·공유 링크·버전 기록·폴더 목록 오류 안내, 링크 해제 확인, 복원 중복 막기, 채팅은 새 메시지만 알림, 공유 토큰 인코딩 |
| 프론트 `AuthProvider.test`(M-3) · `ChatTab.test`(M-4) · `PreviewDialog.test`(M-5) · `Sidebar.test`(M-7) | 로그아웃 실패 시 로그인 상태 유지·안내, 채팅은 HTTP 로 저장 확인 뒤 실패하면 입력 되돌림, 진행 중인 AI 작업 단추 끄기, 안 읽은 표시에 이름 |
| 프론트 순수 함수 `toolAvailability.test` · `trashRules.test` | 형식·설정별 요약·번역 사용 가능 여부와 안내, 휴지통 남은 보관 일수·영구 삭제 문구 (구조 정리 뒤 단위 시험) |

`.env` 의 `APP_PORT` 를 바꿨다면 E2E 에 주소를 알려 주세요: `E2E_BASE_URL=http://localhost:8081 npx playwright test`.

로컬에서 E2E 를 1분 안에 여러 번 돌리면, 모든 요청이 한 IP 로 묶여 로그인 요청 제한(분당 10회)에 걸립니다. 반복 실행할 때는 `.env` 에 `LOGIN_RATE_PER_MINUTE=200`, `LOGIN_ACCOUNT_RATE=200` 을 넣고 `docker compose up -d --wait` 로 다시 띄우세요(CI 의 E2E 작업도 같은 값을 씁니다).

관리자 E2E(`admin.spec.ts`)는 스택과 Playwright 양쪽에 같은 `ADMIN_USERNAME`·`ADMIN_PASSWORD` 가 있어야 돕니다(`.env` 에 넣고 띄운 뒤 `set -a && . ../.env && set +a` 로 셸에도).
E2E 는 실행마다 계정을 3개 가입하므로(관리자 1·긴 이름 2) 한 시간에 두 번 넘게 돌리면 가입 제한(IP 당 시간당 5회)에 걸립니다 — 반복할 때는 `.env` 에 `SIGNUP_RATE_PER_HOUR=200` 도 넣으세요(CI 의 E2E 작업도 같은 값).

E2E 가 실제로 잡아낸 결함: 넓은 화면에서도 모바일용 팀 패널이 숨은 채로 함께 렌더링되어 채팅 요청·DOM 이 중복되던 문제(`useMediaQuery` 로 한 쪽만 렌더링하도록 수정).

## 출시 기준 QA 점검 스크립트

CI 와 별도로, QA 스택(`docker compose -f docker-compose.yml -f qa/compose.qa.yml -p sc-qa up -d --wait`, 8080)에 돌리는 점검 스크립트가 [qa/](../qa) 에 있습니다.
- 백엔드·API: 인가 행렬·인증·CSRF·입력 퍼징·파일 처리·경계값·동시 요청
- 신뢰성: 장애 주입(Toxiproxy)·정합성 검사·백업 복원
- 성능: 부하(k6)·Lighthouse
- AI 요약·번역 실측(실제 Claude 호출, 과금): 임시 스택 `sc-ai` 에 `qa/scripts/ai-live.mjs`(표본 5개, 확인 7개)·`ai-translate-repeat.mjs`·`ai-summary-dates.mjs`(반복) → [qa/results/ai](../qa/results/ai/README.md). 이 실측과 화면 확인에서 결함 6건(AI-06~11)을 찾아 시험을 먼저 더하고 고쳤습니다. QA 스택은 키를 비워 Claude 를 부르지 않습니다
- 개인·팀 드라이브 기능 점검(브라우저, 과금): 임시 스택 `sc-test` 에 `qa/scripts/feature-walkthrough.mjs` — PDF·Word·CSV·Markdown·JSON·PNG 의 미리보기·내려받기·요약·번역·편집·공유 링크·팀 채팅 전송·권한 63개 항목 → [qa/results/walkthrough](../qa/results/walkthrough/README.md)
- 화면: 화면 크롤·접근성 확장 점검(`e2e/qa`, `npx playwright test -c playwright.qa.config.ts`)

방법과 결과는 [QA_2026-10-03](QA_2026-10-03.md) 에 있습니다.
