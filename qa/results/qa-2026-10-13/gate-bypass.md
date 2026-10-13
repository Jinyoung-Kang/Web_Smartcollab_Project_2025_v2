# 접근 코드 잠금 우회 점검 (2026-10-13T10:05:34.477Z)

65건 중 기대와 다름 0건

| ok | name | detail |
|---|---|---|
| OK | GET /api/auth/session | 401 |
| OK | GET /api/public/config | 401 |
| OK | GET / | 401 |
| OK | GET /index.html | 401 |
| OK | GET /drive | 401 |
| OK | GET /assets/ | 401 |
| OK | GET /favicon.svg | 401 |
| OK | GET /actuator/health/../../api/auth/session | 401 |
| OK | GET /actuator/health/..%2f..%2fapi/auth/session | 400 |
| OK | GET /actuator/health/%2e%2e/%2e%2e/api/auth/session | 401 |
| OK | GET /actuator/health;/../../api/auth/session | 401 |
| OK | GET /actuator/health/x;y | 401 |
| OK | GET /actuator/health/liveness/../../../api/public/config | 401 |
| OK | GET /actuator/health%2f..%2f..%2fapi%2fpublic%2fconfig | 400 |
| OK | GET /actuator/health/%252e%252e/api/public/config | 401 |
| OK | GET //api/public/config | 401 |
| OK | GET /API/public/config | 401 |
| OK | GET /api/public/config;jsessionid=x | 401 |
| OK | GET /api/./public/config | 401 |
| OK | GET /api/public//config | 401 |
| OK | GET /__gate/../api/public/config | 404 |
| OK | GET /__gate/%2e%2e/api/public/config | 404 |
| OK | GET /__gate/gate.css/../../api/public/config | 404 |
| OK | GET /__gate//../api/public/config | 404 |
| OK | GET /actuator | 401 |
| OK | GET /actuator/info | 401 |
| OK | GET /actuator/env | 401 |
| OK | GET /actuator/health/../env | 401 |
| OK | GET /v3/api-docs | 401 |
| OK | GET /swagger-ui.html | 401 |
| OK | GET /error | 401 |
| OK | GET /s/abc | 401 |
| OK | GET /api/public/shares/abc | 401 |
| OK | GET /ws | 401 |
| OK | HEAD /api/public/config | 401 |
| OK | OPTIONS /api/public/config | 401 |
| OK | TRACE /api/public/config | 405 |
| OK | PUT /api/public/config | 401 |
| OK | PATCH /api/public/config | 401 |
| OK | DELETE /api/public/config | 401 |
| OK | POST /api/public/config | 401 |
| OK | WebSocket 업그레이드(쿠키 없음) | 401 |
| OK | 쿠키 위조(임의 값) | 401 |
| OK | 쿠키 위조(빈 값) | 401 |
| OK | 쿠키 위조(점 셋) | 401 |
| OK | 쿠키 위조(긴 값) | 400 |
| OK | 다른 출처의 잠금 해제 | 403 |
| OK | 맞는 코드 → 303 + 잠금 쿠키(HttpOnly·SameSite=Lax) | 303 /drive |
| OK | 받은 쿠키로 API | 200 |
| OK | 서명 한 글자 바꾼 쿠키 | 401 |
| OK | 코드 이름을 바꾼 쿠키 | 401 |
| OK | 다음 주소 "//evil.example" | 303 → / |
| OK | 다음 주소 "/\\evil.example" | 303 → / |
| OK | 다음 주소 "/%5Cevil.example" | 303 → /%5Cevil.example |
| OK | 다음 주소 "https://evil.example" | 303 → / |
| OK | 다음 주소 "/\t/evil.example" | 303 → / |
| OK | 다음 주소 "/%09/evil.example" | 303 → /%09/evil.example |
| OK | 다음 주소 "/%2F%2Fevil.example" | 303 → /%2F%2Fevil.example |
| OK | 다음 주소 "/__gate/lock" | 303 → / |
| OK | 다음 주소 "javascript:alert(1)" | 303 → / |
| OK | 다음 주소 "/ /evil.example" | 303 → / /evil.example |
| OK | 잠금화면 next 반사 XSS | 200 |
| OK | 잠금화면 CSP | default-src 'none'; style-src 'self'; form-action 'self'; frame-ancestors 'none' |
| OK | 틀린 코드 7번 → 429 | 429 retry-after=900 |
| OK | X-Forwarded-For 앞쪽 위조로 잠금 우회 시도 | 429 |
