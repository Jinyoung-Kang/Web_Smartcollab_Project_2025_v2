# 관리 전용 계정 점검 (2026-10-13T11:15:09.312Z)

42건 중 기대와 다름 0건

| ok | name | detail |
|---|---|---|
| OK | 관리자 로그인 → role=ADMIN, 드라이브 없음 | {"role":"ADMIN"} |
| OK | 비로그인 GET /api/admin/users | 401 |
| OK | 비로그인 DELETE /api/admin/users/{남} | 401 |
| OK | 일반 사용자 GET /api/admin/users | 403 |
| OK | 일반 사용자 DELETE /api/admin/users/{남} | 403 |
| OK | 관리자 GET /api/folders/tree | 403 |
| OK | 관리자 GET /api/folders/5668 | 403 |
| OK | 관리자 GET /api/teams | 403 |
| OK | 관리자 POST /api/teams | 403 |
| OK | 관리자 GET /api/notifications | 403 |
| OK | 관리자 GET /api/files/search?q=a | 403 |
| OK | 관리자 GET /api/trash | 403 |
| OK | 관리자 GET /api/files/usage | 403 |
| OK | 관리자 POST /api/users/me/delete | 403 |
| OK | 관리자 GET /api/teams/1/messages | 403 |
| OK | 관리자 /ws 업그레이드 | 403 |
| OK | 역할만 바꾼 토큰 → 관리 API | 401 |
| OK | 역할만 바꾼 토큰(Bearer) → 관리 API | 401 |
| OK | alg=none 토큰 → 관리 API | 401 |
| OK | alg=none 토큰(Bearer) → 관리 API | 401 |
| OK | GET /api/admin/users?page=-1 | 400 INVALID_REQUEST |
| OK | GET /api/admin/users?size=0 | 400 INVALID_REQUEST |
| OK | GET /api/admin/users?size=101 | 400 INVALID_REQUEST |
| OK | GET /api/admin/users?page=abc | 400 INVALID_REQUEST |
| OK | GET /api/admin/users?size=1e9 | 400 INVALID_REQUEST |
| OK | GET /api/admin/users?page=2147483647 | 400 INVALID_REQUEST |
| OK | GET /api/admin/users?page=2147483647&size=100 | 400 INVALID_REQUEST |
| OK | GET /api/admin/users?page=99999999999 | 400 INVALID_REQUEST |
| OK | GET /api/admin/users?size=-5 | 400 INVALID_REQUEST |
| OK | GET /api/admin/users?page=1.5 | 400 INVALID_REQUEST |
| OK | DELETE /api/admin/users/-1 | 404 NOT_FOUND |
| OK | DELETE /api/admin/users/0 | 404 NOT_FOUND |
| OK | DELETE /api/admin/users/abc | 400 INVALID_REQUEST |
| OK | DELETE /api/admin/users/99999999999999999999 | 400 INVALID_REQUEST |
| OK | DELETE /api/admin/users/1.5 | 400 INVALID_REQUEST |
| OK | DELETE /api/admin/users/%00 | 400 INVALID_REQUEST |
| OK | DELETE /api/admin/users/9223372036854775807 | 404 NOT_FOUND |
| OK | 관리자 자기 자신 삭제 | 404 |
| OK | 목록에 비밀번호 해시·이메일·폴더 없음 | 200 |
| OK | 동시 삭제 2회 → 한 번만 성공, 500 없음 | 200/404 업로드 404 |
| OK | 삭제된 계정의 세션 → 401 | 401 |
| OK | 삭제된 아이디로 재가입 → 빈 드라이브 | 201 |
