# 보안 설계

## 위협과 대응

| 위협 | 대응 | 위치 |
|---|---|---|
| 다른 사용자의 파일·팀 데이터 접근 (IDOR) | 모든 파일·폴더·팀 요청을 `AccessPolicy` 로 판단. 읽을 수 없으면 404 로 존재 여부도 숨김 | `access/AccessPolicy` |
| 토큰 탈취 (XSS)·스타일 주입 | JWT 를 HttpOnly 쿠키에 보관(자바스크립트로 읽을 수 없음), CSP `script-src 'self'`·`style-src 'self'`(인라인 스타일도 금지, E2E 가 모든 화면의 CSP 위반을 감시) | `AuthCookies`, `SecurityConfig.CSP`, `e2e/tests/fixtures.ts` |
| CSRF | 쿠키 `SameSite=Strict` + SPA CSRF 토큰(`XSRF-TOKEN` → `X-XSRF-TOKEN`). 토큰은 로그인·로그아웃 때만 교체(요청마다 교체하면 요청이 겹칠 때 어긋남) | `SecurityConfig`, `AuthCookies.clearCsrf` |
| WebSocket 도청·사칭 (CSWSH 포함) | 핸드셰이크 쿠키 인증 + 출처 검사, 구독마다 팀 멤버 확인, 보낸 사람은 Principal | `StompAuthorizationInterceptor` |
| WebSocket 구독 폭주 | 한 연결에서 같은 목적지를 다시 구독하면 DB 조회 전에 거절, 연결당 구독 500개. 거절하면 ERROR 프레임 뒤 연결을 닫음 [S-11] | `StompAuthorizationInterceptor` |
| 권한이 사라진 뒤의 WebSocket 수신 | 팀에서 제외·나가기·탈퇴가 커밋되면 그 사용자의 열린 팀 구독을 브로커에서 해제. 로그인(JWT)이 만료된 세션은 1분 안에 닫고, 만료 후의 SUBSCRIBE·SEND 는 거절 | `TeamSubscriptionTracker`, `WebSocketSessionExpiry` |
| 무차별 대입 | 로그인: IP 당 분당 10회 + **계정당 10분 20회**(성공 시 초기화) / 공유 비밀번호: 링크+IP 당 10분 10회 + **링크당 10분 50회** (슬라이딩 윈도). 가입 규칙에 맞지 않는 아이디(악센트 변형 등)는 계정 시도 기록·DB 조회 없이 401 — DB 콜레이션이 악센트를 무시해 변형마다 계정 한도가 따로 생기던 문제 [S-03] | `SlidingWindowRateLimiter`, `AuthService`, `ShareService` |
| 대용량 요청으로 메모리 고갈 (DoS) | 파일 업로드를 뺀 요청 본문을 6MB 로 제한 — 길이를 알리면 읽기 전에, 길이를 알리지 않는 전송(chunked)은 한도를 넘는 순간 413. 업로드는 업로드 한도(200MB)를 따르고, multipart 는 업로드 경로에서만 받음(다른 경로는 읽기 전에 415) [S-18]. 로그인 아이디·비밀번호 길이 상한, 요청 제한기는 키마다 자기 창으로 정리 [S-02] | `RequestBodyLimitFilter`, `AuthDtos`, `SlidingWindowRateLimiter` |
| 한 요청으로 대량 데이터 생성 | 폴더 깊이 50단계(MySQL 재귀 한도 1000 아래), 한 번에 복사하는 폴더 1,000개·같은 항목 중복 제거 [S-04·S-05] | `FolderDepthPolicy`, `ItemTransferService` |
| 외부 유료 API 소진 (번역) | 사용자별 24시간 번역 글자 수 제한(기본 10만 자), 호출이 실패하면 쓴 분량을 돌려줌 [S-16]. DeepL 월 한도 초과(456)는 따로 안내 | `TranslationService`, `SlidingWindowRateLimiter` |
| 공개 가입·업로드 남용 (저장 비용) | 저장 공간 한도(개인 1GB·팀 5GB, 옛 버전·휴지통 포함 실제 저장량 기준, 저장 직전 행 잠금 + 최신 커밋 데이터로 확인), 가입은 IP 당 시간당 5회. 팀마다 한도를 받으므로 한 사람이 팀장인 팀은 10개까지 [S-10] | `StorageQuota`, `AuthService.signUp`, `TeamService.create` |
| 공개 체험 계정 훼손 (데모 모드) | 체험 계정은 탈퇴·팀 삭제·팀장 위임·팀 나가기·체험 계정 내보내기 불가, 체험 한도 50MB, 매일 05:00(Asia/Seoul) 체험 데이터 초기화. 체험 계정은 새 팀을 만들 수 없고 [S-10], 체험 계정 아이디(`demo1~3`)는 데모 모드와 상관없이 가입에 쓸 수 없음 [S-19]. 체험 비밀번호는 공개 설정 API 로 안내되므로 데모 모드는 체험 전용 배포에서만 켜세요 | `DemoAccounts`, `DemoDataSeeder.reset` |
| 요청 제한 우회(IP 위조) | 클라이언트 IP 는 Tomcat RemoteIpValve 가 X-Forwarded-For 를 **오른쪽부터** 읽어 신뢰할 프록시를 건너뛴 첫 주소로 정하고, 포트는 뗍니다. 클라이언트가 헤더 앞쪽에 넣은 값은 쓰이지 않습니다 | `server.forward-headers-strategy: native`, `ClientIp` |
| 계정 존재 여부 추측 (타이밍) | 없는 아이디도 BCrypt 비교를 수행 | `AuthService.authenticate` |
| 휴지통에 넣은 자료의 노출 | 휴지통에 있는 폴더와 그 안의 폴더·파일은 `AccessPolicy` 가 404 로 막아 다운로드·미리보기·편집·공유 링크(410)·채팅 공유·검색·트리에서 모두 사라짐. 복원·영구 삭제는 그 스토리지의 삭제 권한 필요 | `AccessPolicy`, `TrashService` |
| 저장형 XSS (업로드한 HTML·SVG) | 이미지·PDF·텍스트만 inline, 텍스트는 `text/plain` 고정, 나머지는 `attachment` + `nosniff`. 파일 응답에는 앱 CSP 에 `sandbox` 를 더해 브라우저에서 열려도 스크립트 실행·앱 출처 접근 불가(PDF 미리보기는 내장 뷰어를 위해 제외) [S-13] | `FileResponses` |
| 파일 이름으로 확장자 위장 | 글자 방향 제어·보이지 않는 문자(U+202E 등)는 업로드 이름에서 지우고 입력한 이름은 거절. 이모지 결합(ZWJ)·ZWNJ 는 허용 [S-12] | `FileNames` |
| 권한이 줄어든 뒤 남는 권한 | 초대로 들어온 멤버의 편집 권한은 초대자를 따르고, 수락 때 초대자의 초대 권한을 다시 확인 [S-08]. 공유 링크는 쓸 때마다 만든 사람의 공유 권한을 확인(없으면 410, 링크는 보존) [S-09] | `TeamService`, `ShareService`, `AccessPolicy.canShare` |
| 동시 변경으로 인한 데이터 손상 | 폴더 구조 변경은 저장 공간 행을 잠그고 최신 데이터로 다시 판단, 폴더는 바뀐 열만 UPDATE [S-06]. 문서 저장·복원과 서명은 파일 행 잠금으로 차례로 처리 [S-17] | `FolderStructureLock`, `FileContentService` |
| 경로 조작 | 저장소 키는 서버가 만든 UUID 만 사용, 로컬 저장소는 루트 밖 경로 거부 | `LocalBlobStorage.resolve` |
| 공유 비밀번호 노출 | 비밀번호는 본문으로 확인 → 5분짜리 HMAC 허가 발급, URL 에 비밀번호를 싣지 않음 | `DownloadGrantSigner` |
| 공유 링크 추측 | 192bit 무작위 토큰 (`SecureRandom`, URL-safe Base64) | `ShareService.newToken` |
| 비밀값 유출 | 모든 비밀값은 환경 변수, `.env` 는 git 제외, CI 에서 gitleaks 로 전체 이력 검사 | `.env.example`, `ci.yml` |
| 운영 설정 실수 | 운영 프로필에서 `JWT_SECRET` 이 없거나 32바이트 미만이면 기동 실패. `DB_URL`·`DB_USERNAME`·`DB_PASSWORD` 가 없어도 개발용 기본값으로 접속하지 않고 기동 실패 [S-14] | `JwtTokenService`, `ProductionSettings` |
| 공격을 알아채지 못함 (기록·모니터링 부재) | 요청마다 추적 ID(`X-Request-Id`, 오류 본문 `requestId`, 모든 로그 줄), API 접근 기록(쿼리 문자열 제외), `security` 로거에 로그인 성공·실패·요청 제한 발동·공유 비밀번호 실패 기록. 입력한 아이디·비밀번호는 남기지 않고, 접근 기록의 공유 토큰 자리는 `***` 로 가림 [S-07] | `RequestTraceFilter`, `SecurityEventLog` |

## 인증 흐름

```mermaid
sequenceDiagram
    participant B as 브라우저
    participant S as 서버
    B->>S: GET /api/auth/csrf
    S-->>B: Set-Cookie: XSRF-TOKEN (JS 로 읽기 가능)
    B->>S: POST /api/auth/login + X-XSRF-TOKEN
    S-->>B: Set-Cookie: SC_AUTH=<JWT>; HttpOnly; SameSite=Strict
    B->>S: 이후 요청 (쿠키 자동 첨부, 변경 요청엔 X-XSRF-TOKEN)
    S->>S: CookieAuthenticationFilter → JwtDecoder(HS256, 만료·발급자 검증)
```

### 알게 된 함정: Resource Server 와 CSRF

처음에는 OAuth2 Resource Server 의 `BearerTokenResolver` 가 쿠키에서 토큰을 읽도록 했습니다. 그런데 Spring Security 는 "Bearer 토큰이 있는 요청은 CSRF 검사를 생략"하도록 설정하므로, 쿠키 요청까지 CSRF 보호가 꺼졌습니다. 통합 테스트(`AuthFlowTest.mutationWithoutCsrfIsRejected`)가 이를 잡았고, 쿠키 인증을 별도 필터로 분리해 해결했습니다. Resource Server 는 이제 `Authorization` 헤더(브라우저가 자동으로 붙이지 않는 방식)만 처리합니다 → [ADR-0003](adr/0003-cookie-jwt-and-csrf.md)

## 보안 헤더

```
Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self';
  img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self';
  frame-src 'self' blob: https://view.officeapps.live.com; object-src 'none'; base-uri 'self';
  form-action 'self'; frame-ancestors 'self'
Referrer-Policy: same-origin
Permissions-Policy: camera=(), microphone=(), geolocation=()
X-Content-Type-Options: nosniff
X-Frame-Options: SAMEORIGIN
```

파일 다운로드·미리보기 응답(PDF 미리보기 제외)은 위 CSP 끝에 `; sandbox` 를 더해 보냅니다 [S-13].

v1 은 브라우저에서 Babel 이 JSX 를 `eval` 해야 했고 스크립트를 여러 외부 CDN(unpkg, cdn.tailwindcss.com …)에서 받았기 때문에 이런 CSP 를 적용할 수 없었습니다.

## 의존성 취약점

- 프론트엔드·E2E: `npm audit` (2026-10-03 기준 0건)
- 백엔드: 런타임 의존성 좌표를 [OSV](https://osv.dev) 에 조회 (2026-10-03 기준 138개 중 0건)
- Spring Boot 4.1.1 이 관리하는 Jackson 3.1.5·2.22.1 에 2026-09-28~10-01 공개된 파서·역직렬화 DoS 공지 7건(HIGH 5)이 있어, `build.gradle.kts` 에 고쳐진 3.1.7·2.22.3 BOM 을 더했습니다. `DependencyVersionTest` 가 Jackson·Tomcat 이 고쳐진 버전 아래로 내려가지 않게 막습니다 [S-01].
- Spring Boot 4.1.1 이 관리하는 Tomcat 11.0.24 의 CVE-2026-65905·65182·68525 는 Tomcat 자체 인증(DIGEST·FORM)·web.xml 보안 제약에 관한 것이라 이 서비스(Spring Security 필터 사용)에는 직접 해당하지 않지만, Boot 패치 전까지 `build.gradle.kts` 에서 Tomcat 을 11.0.26 으로 고정했습니다.

## 알려진 한계

- 로그아웃은 쿠키 삭제입니다. 이미 탈취된 토큰은 만료 시각까지 유효합니다(HttpOnly 로 탈취 자체를 어렵게 함).
  사용자별 토큰 버전으로 무효화하면 로그아웃 한 번에 그 계정의 모든 기기가 로그아웃되는데, 여러 방문자가 함께 쓰는 체험 계정에서는
  한 사람의 로그아웃이 다른 방문자를 모두 내보내게 됩니다. 필요해지면 토큰마다 ID(jti)를 두고 폐기 목록을 만료 시각까지 보관하는 방식을 권장합니다.
- 요청 제한은 인스턴스별 인메모리입니다. 여러 인스턴스로 확장하면 Redis 등 공유 저장소가 필요합니다.
- 업로드 파일의 악성코드 검사는 하지 않습니다(운영 시 Azure Defender for Storage 등 연동 권장).
- 업로드 경로로 온 multipart 는 인증 전에 CSRF 필터가 본문 파라미터에서 토큰을 찾느라 업로드 한도까지 해석할 수 있습니다.
  보내는 쪽도 같은 양을 보내야 해 증폭은 없습니다. CSRF 토큰을 헤더로만 받게 바꾸는 것이 다음 단계입니다(테스트 지원도 함께 변경 필요).
