# CSRF·보안 헤더 (2026-10-03T10:17:37.097Z)

CSRF 요청 72건 중 403 아님 0건, 부수 효과 없음 true

| ok | req | variant | status | code |
|---|---|---|---|---|
| PASS | POST /api/folders | 토큰 없음 | 403 | CSRF_INVALID |
| PASS | POST /api/folders | 틀린 토큰 | 403 | CSRF_INVALID |
| PASS | POST /api/folders | 다른 사용자 토큰 | 403 | CSRF_INVALID |
| PASS | PATCH /api/folders/5249 | 토큰 없음 | 403 | CSRF_INVALID |
| PASS | PATCH /api/folders/5249 | 틀린 토큰 | 403 | CSRF_INVALID |
| PASS | PATCH /api/folders/5249 | 다른 사용자 토큰 | 403 | CSRF_INVALID |
| PASS | DELETE /api/folders/5249 | 토큰 없음 | 403 | CSRF_INVALID |
| PASS | DELETE /api/folders/5249 | 틀린 토큰 | 403 | CSRF_INVALID |
| PASS | DELETE /api/folders/5249 | 다른 사용자 토큰 | 403 | CSRF_INVALID |
| PASS | PATCH /api/files/20564 | 토큰 없음 | 403 | CSRF_INVALID |
| PASS | PATCH /api/files/20564 | 틀린 토큰 | 403 | CSRF_INVALID |
| PASS | PATCH /api/files/20564 | 다른 사용자 토큰 | 403 | CSRF_INVALID |
| PASS | DELETE /api/files/20564 | 토큰 없음 | 403 | CSRF_INVALID |
| PASS | DELETE /api/files/20564 | 틀린 토큰 | 403 | CSRF_INVALID |
| PASS | DELETE /api/files/20564 | 다른 사용자 토큰 | 403 | CSRF_INVALID |
| PASS | PUT /api/files/20564/content | 토큰 없음 | 403 | CSRF_INVALID |
| PASS | PUT /api/files/20564/content | 틀린 토큰 | 403 | CSRF_INVALID |
| PASS | PUT /api/files/20564/content | 다른 사용자 토큰 | 403 | CSRF_INVALID |
| PASS | POST /api/files/20564/versions/26373/restore | 토큰 없음 | 403 | CSRF_INVALID |
| PASS | POST /api/files/20564/versions/26373/restore | 틀린 토큰 | 403 | CSRF_INVALID |
| PASS | POST /api/files/20564/versions/26373/restore | 다른 사용자 토큰 | 403 | CSRF_INVALID |
| PASS | POST /api/files/20564/signatures | 토큰 없음 | 403 | CSRF_INVALID |
| PASS | POST /api/files/20564/signatures | 틀린 토큰 | 403 | CSRF_INVALID |
| PASS | POST /api/files/20564/signatures | 다른 사용자 토큰 | 403 | CSRF_INVALID |
| PASS | POST /api/files/20564/share-links | 토큰 없음 | 403 | CSRF_INVALID |
| PASS | POST /api/files/20564/share-links | 틀린 토큰 | 403 | CSRF_INVALID |
| PASS | POST /api/files/20564/share-links | 다른 사용자 토큰 | 403 | CSRF_INVALID |
| PASS | DELETE /api/share-links/62 | 토큰 없음 | 403 | CSRF_INVALID |
| PASS | DELETE /api/share-links/62 | 틀린 토큰 | 403 | CSRF_INVALID |
| PASS | DELETE /api/share-links/62 | 다른 사용자 토큰 | 403 | CSRF_INVALID |
| PASS | POST /api/items/move | 토큰 없음 | 403 | CSRF_INVALID |
| PASS | POST /api/items/move | 틀린 토큰 | 403 | CSRF_INVALID |
| PASS | POST /api/items/move | 다른 사용자 토큰 | 403 | CSRF_INVALID |
| PASS | POST /api/items/copy | 토큰 없음 | 403 | CSRF_INVALID |
| PASS | POST /api/items/copy | 틀린 토큰 | 403 | CSRF_INVALID |
| PASS | POST /api/items/copy | 다른 사용자 토큰 | 403 | CSRF_INVALID |
| PASS | POST /api/items/delete | 토큰 없음 | 403 | CSRF_INVALID |
| PASS | POST /api/items/delete | 틀린 토큰 | 403 | CSRF_INVALID |
| PASS | POST /api/items/delete | 다른 사용자 토큰 | 403 | CSRF_INVALID |
| PASS | DELETE /api/trash | 토큰 없음 | 403 | CSRF_INVALID |
| PASS | DELETE /api/trash | 틀린 토큰 | 403 | CSRF_INVALID |
| PASS | DELETE /api/trash | 다른 사용자 토큰 | 403 | CSRF_INVALID |
| PASS | POST /api/teams | 토큰 없음 | 403 | CSRF_INVALID |
| PASS | POST /api/teams | 틀린 토큰 | 403 | CSRF_INVALID |
| PASS | POST /api/teams | 다른 사용자 토큰 | 403 | CSRF_INVALID |
| PASS | DELETE /api/teams/250 | 토큰 없음 | 403 | CSRF_INVALID |
| PASS | DELETE /api/teams/250 | 틀린 토큰 | 403 | CSRF_INVALID |
| PASS | DELETE /api/teams/250 | 다른 사용자 토큰 | 403 | CSRF_INVALID |
| PASS | POST /api/teams/250/invitations | 토큰 없음 | 403 | CSRF_INVALID |
| PASS | POST /api/teams/250/invitations | 틀린 토큰 | 403 | CSRF_INVALID |
| PASS | POST /api/teams/250/invitations | 다른 사용자 토큰 | 403 | CSRF_INVALID |
| PASS | POST /api/teams/250/messages | 토큰 없음 | 403 | CSRF_INVALID |
| PASS | POST /api/teams/250/messages | 틀린 토큰 | 403 | CSRF_INVALID |
| PASS | POST /api/teams/250/messages | 다른 사용자 토큰 | 403 | CSRF_INVALID |
| PASS | DELETE /api/teams/250/messages | 토큰 없음 | 403 | CSRF_INVALID |
| PASS | DELETE /api/teams/250/messages | 틀린 토큰 | 403 | CSRF_INVALID |
| PASS | DELETE /api/teams/250/messages | 다른 사용자 토큰 | 403 | CSRF_INVALID |
| PASS | POST /api/notifications/read-all | 토큰 없음 | 403 | CSRF_INVALID |
| PASS | POST /api/notifications/read-all | 틀린 토큰 | 403 | CSRF_INVALID |
| PASS | POST /api/notifications/read-all | 다른 사용자 토큰 | 403 | CSRF_INVALID |
| PASS | DELETE /api/notifications | 토큰 없음 | 403 | CSRF_INVALID |
| PASS | DELETE /api/notifications | 틀린 토큰 | 403 | CSRF_INVALID |
| PASS | DELETE /api/notifications | 다른 사용자 토큰 | 403 | CSRF_INVALID |
| PASS | POST /api/files/20564/summary | 토큰 없음 | 403 | CSRF_INVALID |
| PASS | POST /api/files/20564/summary | 틀린 토큰 | 403 | CSRF_INVALID |
| PASS | POST /api/files/20564/summary | 다른 사용자 토큰 | 403 | CSRF_INVALID |
| PASS | POST /api/auth/logout | 토큰 없음 | 403 | CSRF_INVALID |
| PASS | POST /api/auth/logout | 틀린 토큰 | 403 | CSRF_INVALID |
| PASS | POST /api/auth/logout | 다른 사용자 토큰 | 403 | CSRF_INVALID |
| PASS | POST /api/users/me/delete | 토큰 없음 | 403 | CSRF_INVALID |
| PASS | POST /api/users/me/delete | 틀린 토큰 | 403 | CSRF_INVALID |
| PASS | POST /api/users/me/delete | 다른 사용자 토큰 | 403 | CSRF_INVALID |

## 보안 헤더

| path | status | content-security-policy | x-content-type-options | x-frame-options | referrer-policy | permissions-policy | strict-transport-security | cache-control | server | x-powered-by | cross-origin-opener-policy | cross-origin-resource-policy |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| / | 200 | default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self | nosniff | SAMEORIGIN | same-origin | camera=(), microphone=(), geolocation=() | — | no-cache | — | — | — | — |
| /login | 200 | default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self | nosniff | SAMEORIGIN | same-origin | camera=(), microphone=(), geolocation=() | — | no-cache | — | — | — | — |
| /drive | 200 | default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self | nosniff | SAMEORIGIN | same-origin | camera=(), microphone=(), geolocation=() | — | no-cache | — | — | — | — |
| /share/abc | 200 | default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self | nosniff | SAMEORIGIN | same-origin | camera=(), microphone=(), geolocation=() | — | no-cache | — | — | — | — |
| /api/public/config | 200 | default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self | nosniff | SAMEORIGIN | same-origin | camera=(), microphone=(), geolocation=() | — | no-cache, no-store, max-age=0, must-revalidate | — | — | — | — |
| /api/auth/me | 200 | default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self | nosniff | SAMEORIGIN | same-origin | camera=(), microphone=(), geolocation=() | — | no-cache, no-store, max-age=0, must-revalidate | — | — | — | — |
| /api/files/20564/download | 200 | default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self | nosniff | SAMEORIGIN | same-origin | camera=(), microphone=(), geolocation=() | — | no-store | — | — | — | — |
| /api/files/20564/view | 200 | default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self | nosniff | SAMEORIGIN | same-origin | camera=(), microphone=(), geolocation=() | — | no-store | — | — | — | — |
| /actuator/health | 200 | default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self | nosniff | SAMEORIGIN | same-origin | camera=(), microphone=(), geolocation=() | — | no-cache, no-store, max-age=0, must-revalidate | — | — | — | — |
| /favicon.svg | 200 | default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self | nosniff | SAMEORIGIN | same-origin | camera=(), microphone=(), geolocation=() | — | no-cache, no-store, max-age=0, must-revalidate | — | — | — | — |
| /does-not-exist.js | 404 | default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self | nosniff | SAMEORIGIN | same-origin | camera=(), microphone=(), geolocation=() | — | no-cache, no-store, max-age=0, must-revalidate | — | — | — | — |
| /assets/index-Ccdq1COi.js | 200 | default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self | nosniff | SAMEORIGIN | same-origin | camera=(), microphone=(), geolocation=() | — | max-age=31536000, public, immutable | — | — | — | — |
