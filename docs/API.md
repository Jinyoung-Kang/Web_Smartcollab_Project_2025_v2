# API 레퍼런스

실행 중인 서버의 **Swagger UI** (`/swagger-ui.html`, 운영 프로필에서는 `SWAGGER_ENABLED=true` 일 때만)에서 요청·응답 스키마를 확인하고 직접 호출해 볼 수 있습니다. 이 문서는 전체 목록과 공통 규칙을 요약합니다.

## 공통 규칙

| 항목 | 규칙 |
|---|---|
| 인증 | 로그인·가입 시 HttpOnly 쿠키 `SC_AUTH`(JWT, 기본 8시간)가 설정됩니다. API 클라이언트는 `Authorization: Bearer <JWT>` 헤더도 쓸 수 있습니다. |
| CSRF | 쿠키로 인증된 `POST/PUT/PATCH/DELETE` 는 `XSRF-TOKEN` 쿠키 값을 `X-XSRF-TOKEN` 헤더로 보내야 합니다. 토큰은 `GET /api/auth/csrf` 로 받습니다. |
| 오류 형식 | RFC 9457 `application/problem+json` — `{ "status", "title", "detail", "code", "requestId", "errors"? }`. 경로에 `%00`·`%2F`·`//`·`;` 가 들어 Tomcat·보안 방화벽이 라우팅 전에 거절한 요청도 같은 형식의 400 입니다 |
| 요청 추적 | 모든 응답에 `X-Request-Id` 헤더. 오류 본문의 `requestId` 와 서버 로그의 추적 ID 가 같습니다 (프록시가 보낸 값은 `[A-Za-z0-9._-]{8,64}` 일 때만 이어 씀) |
| 요청한 화면 | 선택 헤더 `X-Client-Id`(`[A-Za-z0-9-]{8,64}`, 탭마다 하나). 이 요청이 만든 `FOLDER_CHANGED` 이벤트의 `origin` 에 그대로 실려, 보낸 탭은 자기 변경을 다시 불러오지 않습니다. 형식이 맞지 않으면 무시 |
| 일시적 장애 | DB 연결 실패·응답 지연(트랜잭션 30초 초과) 등 잠시 뒤 다시 시도하면 될 수 있는 오류는 503 `SERVICE_UNAVAILABLE` 과 `Retry-After: 5` |
| 본문 크기 | 파일 업로드를 뺀 요청 본문은 6MB 까지 (넘으면 413 `PAYLOAD_TOO_LARGE`). multipart 는 업로드 경로에서만 받습니다(다른 경로는 415 `UNSUPPORTED_MEDIA_TYPE`) |
| 시각 | ISO-8601 UTC (`2026-09-27T02:40:00Z`) |
| 권한 없음 | 읽을 수 없는 대상은 `404`(존재 여부 비공개), 읽을 수 있지만 권한이 부족하면 `403` |

### 오류 코드

| code | HTTP | 의미 |
|---|---|---|
| `INVALID_REQUEST` | 400 | 입력 검증 실패 (`errors` 에 필드별 메시지), 해석할 수 없는 업로드 본문(끝 경계 없음·쓸 수 없는 파일 이름) |
| `UNAUTHORIZED` / `INVALID_CREDENTIALS` | 401 | 로그인 필요 / 아이디·비밀번호 불일치 |
| `FORBIDDEN` / `CSRF_INVALID` | 403 | 권한 부족 / CSRF 토큰 없음·만료 (클라이언트는 토큰을 새로 받아 1회 재시도) |
| `NOT_FOUND` | 404 | 대상 없음 또는 접근 불가 |
| `CONFLICT` / `EDIT_CONFLICT` | 409 | 상태 충돌 / 다른 사람이 먼저 저장함 |
| `LINK_EXPIRED` | 410 | 만료·소진·휴지통 파일의 공유 링크 |
| `PAYLOAD_TOO_LARGE` | 413 | 업로드·편집·요청 본문 한도 초과 |
| `QUOTA_EXCEEDED` | 413 | 저장 공간 한도 초과 (개인 1GB·팀 5GB 기본, 옛 버전·휴지통 포함) |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | 업로드 경로가 아닌 곳으로 보낸 multipart 요청 (본문을 읽기 전에 거절) |
| `RATE_LIMITED` | 429 | 요청 제한 초과 (로그인·가입·공유 비밀번호 시도, 사용자별 하루 번역 분량) |
| `FEATURE_DISABLED` | 503 | 서버에 설정되지 않은 기능 (번역 키 없음, 로컬 저장소에서 Office 미리보기 등) |
| `SERVICE_UNAVAILABLE` | 503 | DB 연결 실패·트랜잭션 시간 초과 등 일시적 장애. `Retry-After` 초 뒤 다시 시도 |

## 엔드포인트

### 인증·계정

| Method | Path | 설명 |
|---|---|---|
| GET | `/api/auth/csrf` | CSRF 토큰 발급 — 쿠키와 같은 값을 돌려주므로 그대로 `X-XSRF-TOKEN` 헤더에 사용 |
| POST | `/api/auth/signup` | 가입 후 바로 로그인 (201). 체험 계정용 아이디(`demo1~3`, 대소문자 무관)는 409 |
| POST | `/api/auth/login` | 로그인 (IP 당 분당 10회 제한) |
| POST | `/api/auth/logout` | 쿠키 삭제 |
| GET | `/api/auth/me` | 내 정보 + 개인 루트 폴더 ID (로그인 전이면 401) |
| GET | `/api/auth/session` | 로그인 여부 — 로그인 전에도 200. `{authenticated, user}`(`user` 는 `/me` 와 같은 모양, 로그인 전이면 `null`). 만료·위조된 쿠키는 지우고 `authenticated=false` |
| POST | `/api/users/me/delete` | 회원 탈퇴 (`{password}` 재확인). 본문이 필요해 DELETE 대신 POST |

### 폴더·파일

| Method | Path | 설명 |
|---|---|---|
| GET | `/api/folders/{id}?limit=&cursor=&sort=&order=` | 폴더 내용 · 경로 · 이 폴더에서의 내 권한. 내용은 나눠서 줍니다 — 아래 [폴더 목록 나누기](#폴더-목록-나누기) |
| GET | `/api/folders/tree?teamId=` | 폴더 트리 (쿼리 1회) |
| POST | `/api/folders` | 폴더 만들기 `{parentId, name}`. 최상위 아래 50단계를 넘으면 400 |
| PATCH | `/api/folders/{id}` | 이름 바꾸기 |
| DELETE | `/api/folders/{id}` | 안의 폴더·파일과 함께 휴지통으로 (30일 뒤 자동 영구 삭제) |
| POST | `/api/files/upload?folderId=` | 업로드 (multipart `file`) |
| GET | `/api/files/{id}` | 파일 정보(이름·크기·미리보기 종류) — 채팅에 공유된 파일 미리보기용, 휴지통에 있거나 읽을 수 없으면 404 |
| GET | `/api/files/{id}/download` | 다운로드 (스트리밍) |
| GET | `/api/files/{id}/view` | 미리보기 (이미지·PDF·텍스트만 inline). PDF 를 뺀 파일 응답은 CSP 에 `sandbox` 가 붙음 |
| GET | `/api/files/{id}/office-preview-url` | Office 미리보기용 10분 SAS URL (Azure 저장소일 때) |
| PATCH | `/api/files/{id}` | 이름 바꾸기 |
| DELETE | `/api/files/{id}` | 휴지통으로 이동 |
| GET / PUT | `/api/files/{id}/content` | 텍스트 내용 / 새 버전 저장 `{content, baseVersionId}` |
| GET | `/api/files/{id}/versions` | 버전 기록 + 서명 |
| POST | `/api/files/{id}/versions/{versionId}/restore` | 버전 되돌리기 |
| POST | `/api/files/{id}/signatures` | 현재 버전에 서명 |
| GET | `/api/files/search?q=&teamId=` | 이름 검색 (최대 100건, 경로 포함) |
| GET | `/api/files/usage?teamId=` | 파일 수·크기(휴지통 제외), 실제 저장량 `storedBytes`(옛 버전·휴지통 포함)·한도 `quotaBytes` |
| POST | `/api/items/move` · `/api/items/copy` | `{items:[{type,id}], targetFolderId}`. 같은 항목은 한 번만 처리. 옮기거나 복사한 뒤 가장 깊은 폴더가 50단계를 넘거나, 한 번에 복사하는 폴더가 1,000개를 넘으면 400 |
| POST | `/api/items/delete` | `{items:[{type,id}]}` — 파일·폴더를 휴지통으로(폴더는 안의 폴더·파일과 함께). 한 트랜잭션(하나라도 실패하면 아무것도 지우지 않음), 최대 200개. 고른 폴더 안의 항목을 함께 고르면 그 폴더와 함께 휴지통으로 감. 응답 `{trashedFiles, trashedFolders}`(고른 항목 수) |
| GET / DELETE | `/api/trash?teamId=` | 휴지통 목록(파일·폴더, `type`·폴더는 `fileCount`) / 비우기 |
| POST | `/api/trash/{fileId}/restore` | 파일 복원 |
| DELETE | `/api/trash/{fileId}` | 파일 영구 삭제 |
| POST | `/api/trash/folders/{id}/restore` | 폴더 복원(안의 폴더·파일 함께). 원래 상위 폴더가 휴지통에 있으면 최상위 폴더로 — 응답 `{folderId, relocated}` |
| DELETE | `/api/trash/folders/{id}` | 폴더 영구 삭제(안의 폴더·파일까지) |

#### 폴더 목록 나누기

| 매개변수 | 기본값 | 설명 |
|---|---|---|
| `limit` | 500 | 한 번에 줄 항목 수, 1~1,000 |
| `cursor` | (없음) | 앞 응답의 `nextCursor` 를 그대로. 해석하지 않는 불투명한 값 |
| `sort` | `name` | `name` · `updatedAt` · `ownerName` · `size` |
| `order` | `asc` | `asc` · `desc` |

- 정렬은 서버가 합니다. 폴더가 늘 앞이고, 선택한 열이 같으면 이름(한국어·숫자 자연 정렬)·ID 순입니다.
- 응답의 `itemCount` 는 폴더의 전체 항목 수, `nextCursor` 는 다음 묶음의 커서(마지막 묶음이면 `null`)입니다.
- 커서는 위치(몇 번째부터)를 담습니다. 묶음을 받는 사이 항목이 더해지거나 빠지면 경계의 항목이 겹치거나 빠질 수 있어, 화면은 실시간 이벤트를 받으면 처음부터 다시 받습니다.
- 잘못된 `limit`·`cursor`·`sort`·`order` 는 400 `INVALID_REQUEST`.

### 문서 도구

| Method | Path | 설명 |
|---|---|---|
| POST | `/api/files/{id}/summary` | 핵심 문장 추출 요약 (단어 빈도 기반, 생성형 아님) |
| POST | `/api/files/{id}/translation?target=EN\|KO` | DeepL 번역 (키 없으면 503, 하루 분량 초과 429) |

### 팀·채팅·알림

| Method | Path | 설명 |
|---|---|---|
| GET / POST | `/api/teams` | 내 팀 목록 / 팀 만들기 (팀장인 팀이 10개면 409, 체험 계정은 403) |
| GET / DELETE | `/api/teams/{id}` | 팀 상세(멤버·내 권한·루트 폴더) / 팀 삭제 |
| GET | `/api/teams/{id}/presence` | 접속 중인 멤버 |
| POST | `/api/teams/{id}/invitations` | 초대 `{username}` |
| POST | `/api/invitations/{id}/accept` · `/reject` | 초대 응답. 편집 권한은 초대한 사람이 편집할 수 있을 때만 받음. 초대한 사람이 더 이상 초대할 수 없으면 수락은 409(거절로 정리) |
| PUT | `/api/teams/{id}/members/{memberId}/permissions` | 권한 변경 (팀장) |
| DELETE | `/api/teams/{id}/members/{memberId}` | 내보내기 (팀장) |
| POST | `/api/teams/{id}/leader/{memberId}` | 팀장 위임 |
| POST | `/api/teams/{id}/leave` | 팀 나가기 |
| GET | `/api/teams/{id}/messages?before=&size=` | 채팅 기록 (커서 페이지) |
| POST / DELETE | `/api/teams/{id}/messages` | 메시지 전송(HTTP) / 기록 비우기(팀장) |
| GET / DELETE | `/api/notifications` | 최근 알림 + 안 읽은 수 / 모두 삭제 |
| POST | `/api/notifications/{id}/read` · `/read-all` | 읽음 처리 |

### 공유 링크

| Method | Path | 인증 | 설명 |
|---|---|---|---|
| GET / POST | `/api/files/{id}/share-links` | 필요 | 링크 목록 / 만들기 `{password?, expiresInHours?, downloadLimit?}` |
| DELETE | `/api/share-links/{linkId}` | 필요 | 링크 해제 |
| GET | `/api/public/shares/{token}` | 불필요 | 파일명·크기·조건. 링크를 만든 사람이 그 파일을 더 이상 공유할 수 없으면 410(링크는 남아 권한이 돌아오면 다시 동작) |
| POST | `/api/public/shares/{token}/unlock` | 불필요 | 비밀번호 확인 → 5분짜리 `grant` |
| GET | `/api/public/shares/{token}/download?grant=` | 불필요 | 다운로드 (횟수 1 차감) |

### 기타

| Method | Path | 설명 |
|---|---|---|
| GET | `/api/public/config` | 기능 사용 가능 여부·업로드 한도·데모 계정(데모 모드일 때) |
| GET | `/actuator/health` | 헬스 체크 (liveness/readiness 포함) |

## WebSocket (STOMP)

- 엔드포인트: `ws(s)://<host>/ws` — 핸드셰이크에 인증 쿠키 필요
- 전송: `SEND /app/teams/{teamId}/chat` 본문 `{ "content": "…" }` 또는 `{ "fileId": 12 }` (팀 파일 공유)
- 구독 목적지와 권한: [ARCHITECTURE.md §5.3](ARCHITECTURE.md#53-실시간--커밋-후-이벤트)
- 한 연결에서 같은 목적지는 한 번만 구독할 수 있고, 연결당 구독은 500개까지입니다. 어기면 서버가 ERROR 프레임을 보내고 연결을 닫습니다.
