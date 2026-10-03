# 인증·세션 점검 (2026-10-03T10:17:02.594Z)

| ok | name | detail |
|---|---|---|
| PASS | SC_AUTH HttpOnly | SC_AUTH=…; Path=/; Max-Age=28800; Expires=Sat, 03 Oct 2026 18:16:57 GMT; HttpOnly; SameSite=Strict |
| PASS | SC_AUTH SameSite=Strict |  |
| PASS | SC_AUTH Path=/ |  |
| PASS | 탈퇴 요청 | status 204 |
| PASS | 탈퇴한 사용자 토큰 GET /api/auth/me | status 401  |
| FAIL | 탈퇴한 사용자 토큰 GET /api/teams | status 200  |
| FAIL | 탈퇴한 사용자 토큰 GET /api/notifications | status 200  |
| FAIL | 탈퇴한 사용자 토큰 GET /api/files/usage | status 200  |
| FAIL | 탈퇴한 사용자 토큰 GET /api/trash | status 200  |
| FAIL | 탈퇴한 사용자 토큰 GET /api/folders/tree | status 200  |
| FAIL | 탈퇴한 사용자 토큰 GET /api/folders/5221 | status 404  |
| PASS | 탈퇴한 사용자 토큰 POST /api/teams | status 401  |
| FAIL | 탈퇴한 사용자 토큰 POST /api/folders | status 404  |
| FAIL | 탈퇴한 사용자 토큰 POST /api/notifications/read-all | status 204  |
| FAIL | 탈퇴한 사용자 토큰 DELETE /api/notifications | status 204  |
| PASS | 탈퇴한 사용자 토큰 POST /api/users/me/delete | status 401  |
| FAIL | 탈퇴한 사용자 토큰 업로드 | status 404 |
| PASS | 변조 토큰 alg=none (cookie) | status 401 |
| PASS | 변조 토큰 alg=none (bearer) | status 401 |
| PASS | 변조 토큰 서명 그대로 sub 변경 (cookie) | status 401 |
| PASS | 변조 토큰 서명 그대로 sub 변경 (bearer) | status 401 |
| PASS | 변조 토큰 서명 제거 (cookie) | status 401 |
| PASS | 변조 토큰 서명 제거 (bearer) | status 401 |
| PASS | 변조 토큰 만료 연장(서명 그대로) (cookie) | status 401 |
| PASS | 변조 토큰 만료 연장(서명 그대로) (bearer) | status 401 |
| PASS | 변조 토큰 HS256→HS512 헤더 (cookie) | status 401 |
| PASS | 변조 토큰 HS256→HS512 헤더 (bearer) | status 401 |
| PASS | 변조 토큰 쓰레기 값 (cookie) | status 401 |
| PASS | 변조 토큰 쓰레기 값 (bearer) | status 401 |
| PASS | 변조 토큰 아주 긴 값(8KB) (cookie) | status 400 |
| PASS | 변조 토큰 아주 긴 값(8KB) (bearer) | status 400 |
| PASS | 정상 쿠키 + 변조 Bearer | status 401 (참고: 우선순위 확인) |
| PASS | 쿠키 인증 + CSRF 없음 → 403 | status 403 |
| PASS | 쿠키 인증 + 엉터리 Bearer + CSRF 없음 → 거절 | status 401 |
| PASS | Bearer 인증은 CSRF 없이 허용(문서) | status 201 |
| PASS | 대소문자만 다른 아이디 가입 거절 | status 409 {"detail":"이미 사용 중인 아이디입니다.","instance":"/api/auth/signup","status":409,"title":"Conflict","code":"C |
| PASS | 소문자로 로그인(참고) | status 200 |
| PASS | 로그인 오류 본문 같음 | 401 INVALID_CREDENTIALS 아이디 또는 비밀번호가 일치하지 않습니다. / 401 INVALID_CREDENTIALS 아이디 또는 비밀번호가 일치하지 않습니다. |
| PASS | 로그인 응답 시간 차이(중앙값) 2배 이내 | 틀린 비밀번호 105.1ms / 없는 아이디 110.8ms |
