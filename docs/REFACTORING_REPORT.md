# v1 → v2 리팩터링 보고서

v1(태그 [`v1.0.0`](https://github.com/Jinyoung-Kang/Web_Smartcollab_Project_2025_v2/tree/v1.0.0))의 모든 코드·메뉴·기능을 검토해 찾은 문제와, v2에서 어떻게 해결했고 어떤 테스트로 확인했는지를 정리합니다.
각 항목의 "근거"는 v1 코드 위치, "확인"은 v2의 자동화 테스트입니다.

- 심각도: **치명**(인증 없이 데이터 노출) · **높음**(다른 사용자 데이터 접근·변조, 기능 불능) · **중간** · **낮음**
- 수치는 모두 이 저장소의 측정 스크립트로 얻은 값입니다 → [PERFORMANCE.md](PERFORMANCE.md)

## 1. 보안

| # | 심각도 | v1 문제 | 근거 (v1) | v2 해결 | 확인 (v2 테스트) |
|---|---|---|---|---|---|
| S1 | 치명 | `/api/files/view/{id}` 가 `permitAll` 이고 권한 검사도 없어, **로그인하지 않은 사람도 파일 ID만 바꿔 가며 모든 파일을 열람** 가능 | `SecurityConfig` permitAll 목록, `FileController.viewFile` | 모든 파일 API 인증 필수 + `AccessPolicy` 로 파일 단위 권한 확인 | `AccessControlTest.anonymousCannotViewFile` |
| S2 | 높음 | 다운로드·Office 미리보기 SAS URL·텍스트 내용·버전 기록·번역/요약 API 에 권한 검사 없음 → **로그인한 누구나 남의 파일 열람** | `FileController.downloadFileById`, `FilePreviewController`, `AIController.translate` | 동일 (`requireFileRead`), 읽을 수 없는 대상은 존재 여부도 숨기도록 404 | `AccessControlTest.strangerCannotReadOthersFile` |
| S3 | 높음 | 채팅 기록·팀원 목록·팀 폴더 트리·팀 검색에 팀 멤버 확인 없음 | `ChatController`, `TeamController.getTeamMembers`, `FolderController.getFolderTree` | `requireMember` | `AccessControlTest.strangerCannotReadTeam`, `…BrowseOthersFolders` |
| S4 | 높음 | 이동 API 가 **대상 폴더만** 검사 → 남의 파일·폴더를 내 폴더로 옮겨 탈취 가능 | `FileService.moveItems` | 원본·대상 모두 편집 권한 확인 | `AccessControlTest.strangerCannotMoveOrCopyOthersFile` |
| S5 | 높음 | 팀원 권한 변경·추방·팀장 위임에서 `memberId` 가 그 팀 소속인지 확인 안 함 → **다른 팀 멤버 조작** 가능 | `TeamService.updateMemberPermissions` 등 | 팀 소속 검증 후 처리 | `AccessControlTest.leaderOfAnotherTeamCannotTouchMembers` |
| S6 | 높음 | 버전 복원에서 `versionId` 가 그 파일의 버전인지 확인 안 함 → 남의 파일 버전을 내 파일로 복원해 내용 열람 | `FileService.restoreVersion` | 파일-버전 소속 검증 | `AccessControlTest.cannotRestoreForeignVersion` |
| S7 | 높음 | WebSocket `/ws` permitAll, STOMP 프레임 인가 없음, 보낸 사람을 **페이로드의 sender 값으로 신뢰** → 아무 팀 채팅 도청·사칭 | `SecurityConfig`, `CollaborationController.sendMessage` | 핸드셰이크 인증 + `StompAuthorizationInterceptor`(구독 시 팀 멤버 확인) + 보낸 사람은 Principal 로 결정 | `WebSocketSecurityTest` (5건, 실제 서버·실제 WebSocket) |
| S8 | 높음 | 탈퇴 사용자 데이터를 넘겨받는 `deleted_user` 계정의 비밀번호가 **공개 저장소 소스에 평문** → 누구나 로그인 가능 | `DataInitializer` | 무작위 비밀번호 해시 + `SYSTEM` 역할은 로그인 거부 | `AuthFlowTest.systemAccountCannotLogin` |
| S9 | 중간 | 관리자 API 가 `User` 엔티티를 그대로 반환(비밀번호 해시 포함). 게다가 첫 가입자가 관리자가 되는 규칙이 시스템 계정 때문에 동작하지 않아 쓸 수도 없음 | `AdminController` | 기능 제거 | `AccessControlTest.adminEndpointsRemoved` |
| S10 | 중간 | JWT 를 `localStorage` 에 저장(XSS 시 탈취), 브라우저에서 Babel 로 JSX 를 실행해야 해서 CSP 적용 불가 | `api.js`, `index.html` | HttpOnly·SameSite=Strict 쿠키 + CSRF 토큰, 번들 빌드로 `script-src 'self'` CSP 적용 | `AuthFlowTest.signUpIssuesHttpOnlyCookie`, `…mutationWithoutCsrfIsRejected`, E2E `보안 헤더` |
| S11 | 중간 | 공유 링크 비밀번호를 URL 쿼리(`?password=`)로 전송 → 서버·프록시 로그와 브라우저 기록에 평문 | `api.js downloadSharedFile` | 비밀번호는 요청 본문으로 확인하고, 서버가 5분짜리 HMAC 서명 허가(grant)를 발급 | `ShareLinkTest.passwordProtectedFlow`, `DownloadGrantSignerTest` |
| S12 | 중간 | 로그인·공유 비밀번호 무차별 대입 제한 없음 | – | 슬라이딩 윈도 요청 제한(IP·토큰 단위) | `AuthFlowTest.loginIsRateLimited`, `SlidingWindowRateLimiterTest` |
| S13 | 낮음 | "특정 사용자에게만 공유" 필드가 있으나 다운로드 시 검사하지 않음(누구나 링크로 접근) | `ShareService.getSharedFile` | 동작하지 않던 기능 제거, 공유 링크는 비밀번호·만료·횟수로 통제 | – |
| S14 | 낮음 | 권한 오류(`SecurityException`)를 처리하지 않아 **500 Internal Server Error** 로 응답 | `GlobalExceptionHandler` | 모든 오류를 RFC 9457 ProblemDetail + 오류 코드로 통일 | `AuthFlowTest.loginFailureIsProblemDetail` 등 |

v2 에서 새로 확인한 보안 함정: 쿠키 토큰을 OAuth2 Resource Server 의 `BearerTokenResolver` 로 읽게 하면 Spring Security 가 "Bearer 토큰 요청은 CSRF 검사 생략" 규칙을 적용해 **쿠키 요청의 CSRF 보호가 꺼집니다**. `AuthFlowTest.mutationWithoutCsrfIsRejected` 가 이를 잡아냈고, 쿠키 인증을 별도 필터(`CookieAuthenticationFilter`)로 분리해 해결했습니다 → [ADR-0003](adr/0003-cookie-jwt-and-csrf.md).

## 2. 기능 결함

| # | 심각도 | v1 문제 | 원인 | v2 해결 | 확인 |
|---|---|---|---|---|---|
| B1 | 높음 | 휴지통에 파일이 있는 폴더는 **삭제 실패** (팀 삭제·회원 탈퇴도 연쇄 실패) | 삭제 시 `isDeleted=false` 파일만 지워 FK 위반 | 재귀 CTE 로 하위 전체를 모아 휴지통 포함 일괄 삭제 | `DriveWorkflowTest.deleteFolderWithTrashedAndSignedFiles` |
| B2 | 높음 | 서명된 파일 **영구 삭제 실패** | 서명·활성 버전 순환 참조를 정리하지 않음 | 참조 순서대로 정리(`DriveCleanupService`) | 위와 같음 |
| B3 | 높음 | 팀 파일을 편집한 적이 있거나 누가 링크를 공유해 준 사용자는 **탈퇴 실패** | `file_versions.editor_id`, `share_links.shared_with_user_id` FK 미정리 | 팀 자료는 '탈퇴한 사용자'로 이관, 개인 자료는 삭제 | `AccountDeletionTest.deleteAccountWithHistory` |
| B4 | 높음 | 복사한 파일을 **내려받을 수 없음** | 복사본에 버전 레코드를 만들지 않음 | 현재 버전을 복사해 첫 버전 생성 | `DriveWorkflowTest.copiedFileIsDownloadable` |
| B5 | 중간 | 복사가 현재 내용이 아닌 **최초 업로드본**을 복사 | `storedName`(원본) 기준 복사 | 활성 버전 기준 복사 | 위와 같음 |
| B6 | 높음 | 폴더를 자기 하위 폴더로 옮기면 순환 구조 → 트리·검색 **무한 재귀** | 순환 검사 없음 | 재귀 CTE 로 하위 트리 검사 | `DriveWorkflowTest.moveIntoOwnSubtreeIsRejected` |
| B7 | 중간 | 팀 폴더를 개인 드라이브로 옮기면 폴더는 개인·내부 파일은 팀 소속인 모순 상태 | `folder.team` 미갱신 | 스토리지 간 이동 금지(복사 사용) | `DriveWorkflowTest.crossScopeMoveIsRejected` |
| B8 | 높음 | 이메일 없이 가입한 **두 번째 사용자부터 가입 실패(500)** | 빈 문자열 `""` 저장 → UNIQUE 충돌 | 빈 값은 NULL 로 정규화 | `AuthFlowTest.blankEmailDoesNotCollide` |
| B9 | 중간 | 팀 화면에서 폴더 ID 와 팀 ID 를 같은 변수에 섞어 써서, 하위 폴더 ID 가 팀 ID 와 같으면 업로드 위치·새로고침·경로 이동이 틀어짐 | `dashboard.js` 의 `viewContext.id` | 모든 화면을 폴더 ID 기반 URL 로 통일 (`/teams/{t}/folders/{f}`) | E2E 시나리오 |
| B10 | 중간 | 다운로드 파일명 깨짐: 공백이 `+`, 공유 다운로드는 **첫 `_` 앞부분이 잘림** | `URLEncoder` 사용, `getOriginalFilenameFromStored` 오용 | RFC 6266 `filename*=UTF-8''` | `DriveWorkflowTest.uploadListDownload`, `ShareLinkTest.passwordProtectedFlow` |
| B11 | 중간 | 공유 링크 만료 시각이 **9시간 일찍 만료** | 브라우저가 UTC(`…Z`)로 보낸 값을 서버(Jackson 2.15.4, v1 버전으로 직접 확인)가 시간대 없이 읽고 KST 현재 시각과 비교 | 서버는 `Instant`(UTC) 저장, 클라이언트는 "유효 시간(시간 단위)"만 전송 | `ShareLinkTest.expiryAndRevoke` |
| B12 | 중간 | 다운로드 횟수 제한을 동시 요청으로 **초과 가능** | 읽기-비교-저장 분리 | 조건부 UPDATE 로 원자적 차감 | `ShareLinkTest.downloadLimitIsAtomic` (12개 동시 요청 → 정확히 3회) |
| B13 | 중간 | 옛 버전 복원 후 서명하면 **엉뚱한 버전에 서명** | "가장 최근 생성된 버전"에 서명 | 현재(활성) 버전에 서명, SHA-256 기록 | `TextEditingTest.signatureFollowsActiveVersion` |
| B14 | 중간 | 두 사람이 같은 문서를 편집하면 **나중 저장이 앞사람 변경을 덮어씀**(lost update) | 동시성 제어 없음 | 기준 버전 비교 + JPA `@Version` 낙관적 잠금 → 409 와 해결 화면 | `TextEditingTest.staleSaveIsRejected` |
| B15 | 중간 | 삭제한 파일을 볼·되살릴 **휴지통 화면이 없고** 영구 삭제도 안 돼 저장소에 계속 쌓임 | API 만 존재 | 개인/팀 휴지통 화면, 30일 후 자동 영구 삭제 | `DriveWorkflowTest.trashLifecycle` |
| B16 | 중간 | 설정에 `mode: local` 이 있지만 구현이 없어 **Azure 계정 없이는 실행 불가** | Azure SDK 직접 의존 | `BlobStorage` 전략 패턴(로컬/Azure) | `LocalBlobStorageTest`, `AzureBlobStorageTest`(Azurite) |
| B17 | 낮음 | "요약"은 앞 150자를 자른 **모의 결과**, 번역 키가 없으면 `[MOCK]` 문자열을 결과처럼 표시 | `AIService`, `DeepLTranslationService` | 단어 빈도 기반 추출 요약(원문 문장만, 생성형 아님을 명시) · 키 없으면 버튼 비활성·503 | `TextEditingTest.summaryIsExtractive`, `…translationWithoutKeyIsDisabled` |
| B18 | 낮음 | 실시간 공동 편집 코드가 **연결·구독되지 않는 죽은 코드** | `Realtime.connect` 미호출 | 제거하고 낙관적 잠금으로 대체 | – |
| B19 | 낮음 | 접속자 정보를 서버가 보내지만 화면에서 쓰지 않았고, 탭 하나만 닫아도 퇴장 처리 | 사용자 이름 집합만 관리 | (세션, 구독) 단위 추적, 멤버 목록에 접속 표시 | `WebSocketSecurityTest.presence` |
| B20 | 낮음 | 광고·공지 패널에 실제와 무관한 문구("…최저가!") | `InfoPanel` | 제거, 실제 사용량 표시로 대체 | – |
| B21 | 낮음 | 운영 DB 스키마를 `ddl-auto: update` 로 자동 변경, JWT 만료 시간 주석(8h)·코드(12h)·설정(3h, 미사용) 불일치 | `application*.yml`, `JwtUtil` | Flyway 마이그레이션 + `validate`, 설정 한 곳(`JWT_TTL`) | 모든 통합 테스트가 Flyway 스키마로 실행 |
| B22 | 낮음 | Lombok 생성자에 `@Lazy` 가 복사되지 않아 의도한 지연 주입이 동작하지 않음 | `@Lazy private final` | 순환 의존 제거로 불필요 | – |

## 3. 성능

| 항목 | v1 | v2 | 방법 |
|---|---|---|---|
| 폴더 트리 조회 (폴더 62개) | SQL **63회** (폴더 수 + 1) | **1회** | 범위 전체를 한 번에 읽어 O(n) 트리 구성 |
| 파일 검색 (폴더 62개) | SQL **125회** | **2회** | 폴더 목록 1회 + `IN` 검색 1회 |
| 32MB 파일 다운로드 시 서버 힙 할당 | **100.7MB** | **17KB** | 전체 버퍼링 → 스트리밍 |
| 로그인 화면 JS 전송량 | **1,156KB** | **133KB** (-88%) | CDN 개발용 React·Babel → Vite 번들 + gzip |
| 로그인 화면까지 메인 스레드 스크립트 실행 | **566ms** | **33ms** (-94%) | 브라우저 JSX 변환 제거 |
| 팀별 WebSocket 연결 | 팀 수만큼 | **1개** | 구독만 여러 개 |
| 채팅 기록 | 전체를 한 번에 | **30개씩 커서 페이지** | `(team_id, message_id)` 인덱스 |
| 알림 | 10초마다 폴링 | **WebSocket 즉시 전달** | 사용자 큐 `/user/queue/notifications` |

측정 조건과 원자료: [PERFORMANCE.md](PERFORMANCE.md), [measurements/](measurements/)

## 4. UI/UX

| v1 | v2 |
|---|---|
| 새로고침하면 항상 첫 화면 (URL 라우팅 없음) | 모든 화면이 주소를 가짐 (딥링크·뒤로 가기) |
| 결과·오류를 `alert()` / 확인을 `confirm()`·`prompt()` 로 표시 | 토스트 알림, 접근성 있는 확인 대화상자(네이티브 `<dialog>`) |
| 파일 1개씩, 진행률 없이 업로드 | 여러 파일·드래그 앤 드롭·동시 3개·진행률·취소 |
| 휴지통·버전 서명·공유 링크 관리 화면 없음 | 휴지통, 버전 기록(현재 버전·서명 유효/무효·SHA-256), 공유 링크 목록·해제 |
| 고정 3단 레이아웃 (모바일 사용 불가) | 반응형: 좁은 화면은 메뉴·팀 패널을 서랍(drawer)으로 |
| 파일 크기는 항상 KB, 정렬은 문자 코드 순 | B/KB/MB 자동 단위, 한국어 자연 정렬("파일 2" < "파일 10") |
| 편집 중 저장 충돌 알림 없음 | 충돌 시 "최신 내용 불러오기 / 내 내용으로 새 버전 저장" 선택 |
| 권한 없는 버튼도 보이고 누르면 오류 | 권한에 맞춰 버튼 표시·읽기 전용 안내 |
