# 인가 행렬 결과 (2026-10-03T07:58:02.206Z)

요청 80건 중 기대와 다름 0건, 부수 효과 확인 17건 중 실패 0건

| ok | who | req | expect | actual | note | body |
|---|---|---|---|---|---|---|
| PASS | B | GET /api/folders/1 | 404 | 404 |  |  |
| PASS | B | GET /api/folders/5 | 404 | 404 |  |  |
| PASS | B | POST /api/folders | 404 | 404 |  |  |
| PASS | B | PATCH /api/folders/5 | 404 | 404 |  |  |
| PASS | B | DELETE /api/folders/5 | 404 | 404 |  |  |
| PASS | B | GET /api/files/1 | 404 | 404 |  |  |
| PASS | B | GET /api/files/1/download | 404 | 404 |  |  |
| PASS | B | GET /api/files/1/view | 404 | 404 |  |  |
| PASS | B | GET /api/files/1/office-preview-url | 404/503 | 404 |  |  |
| PASS | B | GET /api/files/1/content | 404 | 404 |  |  |
| PASS | B | GET /api/files/1/versions | 404 | 404 |  |  |
| PASS | B | GET /api/files/1/share-links | 404 | 404 |  |  |
| PASS | B | PATCH /api/files/1 | 404 | 404 |  |  |
| PASS | B | PUT /api/files/1/content | 404 | 404 |  |  |
| PASS | B | POST /api/files/1/versions/1/restore | 404 | 404 |  |  |
| PASS | B | POST /api/files/1/signatures | 404 | 404 |  |  |
| PASS | B | POST /api/files/1/share-links | 404 | 404 |  |  |
| PASS | B | POST /api/files/1/summary | 404 | 404 |  |  |
| PASS | B | POST /api/files/1/translation?target=EN | 404 | 404 |  |  |
| PASS | B | POST /api/items/move | 404 | 404 |  |  |
| PASS | B | POST /api/items/copy | 404 | 404 |  |  |
| PASS | B | POST /api/items/copy | 404 | 404 |  |  |
| PASS | B | POST /api/items/delete | 404 | 404 |  |  |
| PASS | B | POST /api/items/move | 404 | 404 | 내 파일을 남의 폴더로 |  |
| PASS | B | POST /api/items/copy | 404 | 404 | 내 파일을 남의 폴더로 복사 |  |
| PASS | B | POST /api/trash/2/restore | 404 | 404 |  |  |
| PASS | B | DELETE /api/trash/2 | 404 | 404 |  |  |
| PASS | B | POST /api/trash/folders/6/restore | 404 | 404 |  |  |
| PASS | B | DELETE /api/trash/folders/6 | 404 | 404 |  |  |
| PASS | B | DELETE /api/share-links/1 | 404 | 404 |  |  |
| PASS | B | POST /api/notifications/7/read | 404 | 404 |  |  |
| PASS | B | DELETE /api/notifications/7 | 404 | 404 |  |  |
| PASS | C | POST /api/invitations/3/accept | 404 | 404 | A 에게 온 초대를 C 가 수락 |  |
| PASS | C | POST /api/invitations/3/reject | 404 | 404 | A 에게 온 초대를 C 가 거절 |  |
| PASS | B | POST /api/invitations/3/accept | 404 | 404 | 보낸 사람(B)이 자기 초대를 수락 |  |
| PASS | B | GET /api/teams/1 | 404 | 404 |  |  |
| PASS | B | GET /api/teams/1/presence | 404 | 404 |  |  |
| PASS | B | GET /api/teams/1/messages | 404 | 404 |  |  |
| PASS | B | POST /api/teams/1/messages | 404 | 404 |  |  |
| PASS | B | DELETE /api/teams/1/messages | 404 | 404 |  |  |
| PASS | B | POST /api/teams/1/invitations | 404 | 404 | 스스로 초대 |  |
| PASS | B | PUT /api/teams/1/members/2/permissions | 404 | 404 |  |  |
| PASS | B | DELETE /api/teams/1/members/2 | 404 | 404 |  |  |
| PASS | B | POST /api/teams/1/leave | 404 | 404 |  |  |
| PASS | B | POST /api/teams/1/leader/2 | 404 | 404 |  |  |
| PASS | B | DELETE /api/teams/1 | 404 | 404 |  |  |
| PASS | B | GET /api/folders/tree?teamId=1 | 403/404 | 404 |  |  |
| PASS | B | GET /api/files/search?q=team&teamId=1 | 403/404 | 404 |  |  |
| PASS | B | GET /api/files/usage?teamId=1 | 403/404 | 404 |  |  |
| PASS | B | GET /api/trash?teamId=1 | 403/404 | 404 |  |  |
| PASS | B | DELETE /api/trash?teamId=1 | 403/404 | 404 |  |  |
| PASS | B | GET /api/folders/7 | 404 | 404 |  |  |
| PASS | B | GET /api/files/3/download | 404 | 404 |  |  |
| PASS | B | PUT /api/teams/2/members/2/permissions | 400/403/404/409 | 404 | T2 경로 + T1 멤버 |  |
| PASS | B | DELETE /api/teams/2/members/2 | 400/403/404/409 | 404 | T2 경로 + T1 멤버 |  |
| PASS | B | POST /api/teams/2/leader/2 | 400/403/404/409 | 404 | T2 경로 + T1 멤버 |  |
| PASS | B | POST /api/files/4/versions/1/restore | 400/403/404/409 | 404 | B 파일 + A 파일의 버전 |  |
| PASS | B | PUT /api/files/4/content | 400/403/404/409 | 409 | B 파일 + A 파일의 버전을 기준으로 |  |
| PASS | B | POST /api/teams/2/messages | 400/403/404/409 | 404 | T2 채팅에 A 개인 파일 |  |
| PASS | B | POST /api/teams/2/messages | 400/403/404/409 | 404 | T2 채팅에 T1 파일 |  |
| PASS | B | POST /api/teams/2/messages | 400/403/404/409 | 404 | T2 채팅에 B 개인 파일 |  |
| PASS | C | POST /api/folders | 403 | 403 |  |  |
| PASS | C | PATCH /api/folders/8 | 403 | 403 |  |  |
| PASS | C | DELETE /api/folders/8 | 403 | 403 |  |  |
| PASS | C | PATCH /api/files/3 | 403 | 403 |  |  |
| PASS | C | DELETE /api/files/3 | 403 | 403 |  |  |
| PASS | C | POST /api/items/delete | 403 | 403 |  |  |
| PASS | C | POST /api/items/move | 403 | 403 | 팀 파일을 내 드라이브로 이동 |  |
| PASS | C | PUT /api/files/3/content | 403 | 403 |  |  |
| PASS | C | POST /api/files/3/versions/4/restore | 403/409 | 403 |  |  |
| PASS | C | POST /api/files/3/signatures | 403 | 403 |  |  |
| PASS | C | POST /api/teams/1/invitations | 403 | 403 |  |  |
| PASS | C | PUT /api/teams/1/members/2/permissions | 403 | 403 | 자기 권한 올리기 |  |
| PASS | C | DELETE /api/teams/1/messages | 403 | 403 |  |  |
| PASS | C | DELETE /api/teams/1 | 403 | 403 |  |  |
| PASS | C | DELETE /api/trash?teamId=1 | 403 | 403 |  |  |
| PASS | C | POST /api/teams/1/leader/2 | 403 | 403 |  |  |
| PASS | C | POST /api/teams/1/messages | 200/201 | 201 | 읽기 전용도 팀 파일 공유는 가능(기준) |  |
| PASS | anon | GET /api/public/shares/H9UNsEOTSr61jJj-aWt6ngSOOqlhymjE/download?grant=… | 400/401/403/404/410 | 403 | A 링크의 grant 로 B 링크 다운로드 |  |
| PASS | anon | GET /api/public/shares/M7OfTk05cTeWJt7szymVczz_YQ5VXrFj/download | 400/401/403 | 403 | grant 없이 비밀번호 링크 |  |

## 부수 효과

| ok | name | expected | actual |
|---|---|---|---|
| PASS | A 파일 내용 | "A secret v2" | "A secret v2" |
| PASS | A 파일 이름 | "a.txt" | "a.txt" |
| PASS | A 폴더 이름 | "a-folder" | "a-folder" |
| PASS | A 폴더 안 항목 수 | 0 | 0 |
| PASS | A 공유 링크 활성 | true | true |
| PASS | A 휴지통 파일 그대로 | true | true |
| PASS | A 휴지통 폴더 그대로 | true | true |
| PASS | T1 의 C 권한 | [false,false,false] | [false,false,false] |
| PASS | T1 팀장 | "qa_e3db3a" | "qa_e3db3a" |
| PASS | T1 멤버 수 | 2 | 2 |
| PASS | T1 채팅에 B 메시지 없음 | false | false |
| PASS | A 알림 그대로(안 읽음) | false | false |
| PASS | A 에게 온 초대 상태 | "PENDING" | "PENDING" |
| PASS | B 파일 내용 | "B own v2" | "B own v2" |
| PASS | T2 채팅에 남의 파일 없음 | 0 | 0 |
| PASS | T2 팀장 | "qa_kof74c" | "qa_kof74c" |
| PASS | B 에게 온 초대 그대로 | "PENDING" | "PENDING" |
