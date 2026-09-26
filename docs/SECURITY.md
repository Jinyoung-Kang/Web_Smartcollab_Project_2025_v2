# 보안 설계

## 위협과 대응

| 위협 | 대응 | 위치 |
|---|---|---|
| 다른 사용자의 파일·팀 데이터 접근 (IDOR) | 모든 파일·폴더·팀 요청을 `AccessPolicy` 로 판단. 읽을 수 없으면 404 로 존재 여부도 숨김 | `access/AccessPolicy` |
| 토큰 탈취 (XSS) | JWT 를 HttpOnly 쿠키에 보관(자바스크립트로 읽을 수 없음), CSP `script-src 'self'` | `AuthCookies`, `SecurityConfig.CSP` |
| CSRF | 쿠키 `SameSite=Strict` + SPA CSRF 토큰(`XSRF-TOKEN` → `X-XSRF-TOKEN`) | `SecurityConfig` |
| WebSocket 도청·사칭 (CSWSH 포함) | 핸드셰이크 쿠키 인증 + 출처 검사, 구독마다 팀 멤버 확인, 보낸 사람은 Principal | `StompAuthorizationInterceptor` |
| 무차별 대입 | 로그인: IP 당 분당 10회 + **계정당 10분 20회**(성공 시 초기화) / 공유 비밀번호: 링크+IP 당 10분 10회 + **링크당 10분 50회** (슬라이딩 윈도) | `SlidingWindowRateLimiter`, `AuthService`, `ShareService` |
| 요청 제한 우회(IP 위조) | 클라이언트 IP 는 Tomcat RemoteIpValve 가 X-Forwarded-For 를 **오른쪽부터** 읽어 신뢰할 프록시를 건너뛴 첫 주소로 정하고, 포트는 뗍니다. 클라이언트가 헤더 앞쪽에 넣은 값은 쓰이지 않습니다 | `server.forward-headers-strategy: native`, `ClientIp` |
| 계정 존재 여부 추측 (타이밍) | 없는 아이디도 BCrypt 비교를 수행 | `AuthService.authenticate` |
| 저장형 XSS (업로드한 HTML·SVG) | 이미지·PDF·텍스트만 inline, 텍스트는 `text/plain` 고정, 나머지는 `attachment` + `nosniff` | `FileResponses` |
| 경로 조작 | 저장소 키는 서버가 만든 UUID 만 사용, 로컬 저장소는 루트 밖 경로 거부 | `LocalBlobStorage.resolve` |
| 공유 비밀번호 노출 | 비밀번호는 본문으로 확인 → 5분짜리 HMAC 허가 발급, URL 에 비밀번호를 싣지 않음 | `DownloadGrantSigner` |
| 공유 링크 추측 | 192bit 무작위 토큰 (`SecureRandom`, URL-safe Base64) | `ShareService.newToken` |
| 비밀값 유출 | 모든 비밀값은 환경 변수, `.env` 는 git 제외, CI 에서 gitleaks 로 전체 이력 검사 | `.env.example`, `ci.yml` |
| 운영 설정 실수 | 운영 프로필에서 `JWT_SECRET` 이 없거나 32바이트 미만이면 기동 실패 | `JwtTokenService` |

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
Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline';
  img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self';
  frame-src 'self' blob: https://view.officeapps.live.com; object-src 'none'; base-uri 'self';
  form-action 'self'; frame-ancestors 'self'
Referrer-Policy: same-origin
Permissions-Policy: camera=(), microphone=(), geolocation=()
X-Content-Type-Options: nosniff
X-Frame-Options: SAMEORIGIN
```

v1 은 브라우저에서 Babel 이 JSX 를 `eval` 해야 했고 스크립트를 여러 외부 CDN(unpkg, cdn.tailwindcss.com …)에서 받았기 때문에 이런 CSP 를 적용할 수 없었습니다.

## 알려진 한계

- 로그아웃은 쿠키 삭제입니다. 이미 탈취된 토큰은 만료 시각까지 유효합니다(HttpOnly 로 탈취 자체를 어렵게 함).
- 요청 제한은 인스턴스별 인메모리입니다. 여러 인스턴스로 확장하면 Redis 등 공유 저장소가 필요합니다.
- 업로드 파일의 악성코드 검사는 하지 않습니다(운영 시 Azure Defender for Storage 등 연동 권장).
