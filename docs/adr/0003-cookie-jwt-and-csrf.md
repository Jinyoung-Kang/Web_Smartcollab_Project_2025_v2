# ADR-0003 JWT 를 HttpOnly 쿠키로, CSRF 토큰 요구

## 배경
v1 은 JWT 를 `localStorage` 에 저장해 XSS 한 번이면 토큰이 유출될 수 있었고, `<iframe>`·`<a download>` 가 헤더를 못 붙이는 문제를 피하려고 파일 보기 API 를 **인증 없이 공개**했습니다(치명적 결함 S1).

## 결정
- 로그인 시 JWT(HS256, 발급자·만료 검증)를 `HttpOnly; SameSite=Strict; Secure(운영)` 쿠키로 발급
- 쿠키 인증 요청의 상태 변경에는 CSRF 토큰(`XSRF-TOKEN` 쿠키 → `X-XSRF-TOKEN` 헤더) 요구
- 쿠키 인증은 전용 필터 `CookieAuthenticationFilter` 가 처리하고, OAuth2 Resource Server 는 `Authorization` 헤더만 처리
  - 이유: Resource Server 의 `BearerTokenResolver` 로 쿠키를 읽으면 Spring Security 가 "Bearer 요청은 CSRF 생략" 규칙을 적용해 쿠키 요청의 CSRF 보호가 꺼짐 — 통합 테스트가 발견
- 공개 경로(로그인·공유·정적 파일)에서는 토큰을 해석하지 않아, 만료된 쿠키가 남아 있어도 로그인 화면이 막히지 않음. 위조·만료 쿠키는 응답에서 삭제
- 서버는 세션을 저장하지 않음 (stateless)

## 결과
- `<img>`·`<iframe>`·`<a download>` 가 쿠키로 인증되어 미리보기·다운로드가 스트리밍으로 동작 (JS 로 blob 을 만들 필요 없음)
- WebSocket 핸드셰이크도 같은 쿠키로 인증
- 대가: 로그아웃해도 탈취된 토큰은 만료까지 유효 (HttpOnly 로 탈취 가능성 자체를 낮춤). 즉시 무효화가 필요하면 토큰 버전·차단 목록 도입
