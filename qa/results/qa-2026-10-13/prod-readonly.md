# 운영 익명 읽기 전용 점검 (2026-10-13T10:29:34.381Z)

요청 약 38건(GET/HEAD/OPTIONS·TLS 연결), 계정·접근 코드·쓰기 없음. 운영 주소는 <운영>으로 가림.

33건 중 기대와 다름 0건

| ok | 점검 | 결과 |
|---|---|---|
| OK | TLS 1.2 이상·인증서 유효 | {"protocol":"TLSv1.3","cipher":"TLS_AES_256_GCM_SHA384","validTo":"Jan 19 14:21:41 2027 GMT","authorized":true} |
| OK | 옛 프로토콜 TLSv1 거절 | ERR_SSL_NO_PROTOCOLS_AVAILABLE |
| OK | 옛 프로토콜 TLSv1.1 거절 | ERR_SSL_NO_PROTOCOLS_AVAILABLE |
| OK | HTTP 는 HTTPS 로 전환(301/308) | 301 → https://<운영>/ |
| OK | 잠금화면(401 HTML) | 401 |
| OK | 잠금화면 CSP | default-src 'none'; style-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none' |
| OK | HSTS | max-age=31536000 ; includeSubDomains |
| OK | nosniff | nosniff |
| OK | frame 차단(X-Frame-Options 또는 frame-ancestors) | DENY |
| OK | Referrer-Policy | same-origin |
| OK | Cache-Control no-store(잠금화면) | no-store |
| OK | 서버 정보 노출 없음(Server·X-Powered-By) | server= x-powered-by= |
| OK | API 는 401 GATE_LOCKED(problem+json) | 401 application/problem+json;charset=UTF-8 |
| OK | 헬스 체크 200(상세 없음) | 200 {"groups":["liveness","readiness"],"status":"UP"} |
| OK | 잠김 /actuator | 401 |
| OK | 잠김 /actuator/info | 401 |
| OK | 잠김 /actuator/env | 401 |
| OK | 잠김 /actuator/health/../../api/public/config | 401 |
| OK | 잠김 /actuator/health;/../../api/public/config | 401 |
| OK | 잠김 /actuator/health/%2e%2e/%2e%2e/api/public/config | 401 |
| OK | 잠김 //api/public/config | 401 |
| OK | 잠김 /API/public/config | 401 |
| OK | 잠김 /__gate/../api/public/config | 401 |
| OK | 잠김 /__gate/%2e%2e/api/public/config | 401 |
| OK | 잠김 /v3/api-docs | 401 |
| OK | 잠김 /swagger-ui.html | 401 |
| OK | 잠김 /index.html | 401 |
| OK | 잠김 /assets/ | 401 |
| OK | 잠김 /error | 401 |
| OK | 잠김 /ws | 401 |
| OK | HEAD /api/public/config 잠김 | 401 |
| OK | OPTIONS /api/public/config 잠김 | 401 |
| OK | 잠금화면 next 반사 XSS 없음 | 200 |
