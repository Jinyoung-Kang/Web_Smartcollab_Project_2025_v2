# ADR-0001 번들 SPA 를 Spring Boot 가 같은 출처로 제공

## 배경
v1 은 `index.html` 에서 React **개발용** 빌드, Babel Standalone, Tailwind Play CDN, 최신 버전 고정 없는 lucide 를 외부 CDN 으로 받고, JSX 를 브라우저에서 변환했습니다.
- 첫 화면까지 JS 1,156KB 전송, 메인 스레드 스크립트 578ms (측정: [PERFORMANCE.md](../PERFORMANCE.md))
- 브라우저 `eval` 과 여러 외부 출처 때문에 CSP 적용 불가
- 타입 검사·린트·테스트 불가, 전역 변수로 파일 간 의존

## 결정
- `frontend/` 를 Vite + React 19 + TypeScript 프로젝트로 분리하고, 빌드 결과를 Spring Boot jar 의 `static/` 에 넣어 **같은 출처**로 제공합니다 (Docker 멀티 스테이지, `./gradlew bootJar -PbundleFrontend`).
- 클라이언트 라우팅 경로는 `SpaController` 가 `index.html` 로 전달합니다. 경로를 나열하지 않고 "서버 경로(api·ws·actuator·v3·swagger-ui·assets·error)가 아니고 점(.)이 없는 GET" 규칙으로 판단합니다 — 나열 방식에서는 목록에서 빠진 `/search` 가 새로고침 시 404 였습니다([BUG-02]).
- 해시 파일명 자산은 1년 `immutable` 캐시, `index.html` 은 `no-cache`.

## 결과
- JS 151KB(-87%), 스크립트 실행 35ms(-94%), `script-src 'self'` CSP 적용 (2026-09 재측정, 데이터 라우터 도입 후)
- 같은 출처이므로 CORS 불필요, 쿠키를 `SameSite=Strict` 로 둘 수 있음 (ADR-0003)
- 배포 단위가 하나 — 프론트와 백엔드 버전이 어긋나지 않음
- 대가: 프론트만 따로 CDN 에 배포하는 구조는 아님 (필요 시 `CORS_ALLOWED_ORIGINS` 로 분리 가능)
