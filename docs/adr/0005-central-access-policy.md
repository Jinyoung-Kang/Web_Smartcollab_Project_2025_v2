# ADR-0005 권한 판단을 한 곳으로

## 배경
v1 은 `checkFolderPermission` 류의 메서드를 서비스 4곳에 따로 두었고 규칙이 서로 달랐습니다(예: 한 곳은 폴더 소유자면 팀 편집 권한이 없어도 허용). 다운로드·미리보기·채팅 기록 등 여러 API 는 검사 자체가 없었습니다.

## 결정
- `AccessPolicy` 가 파일·폴더·팀 권한을 판단하는 유일한 진입점 (`requireRead/Edit/Delete`, `requireFileRead/Edit/Delete`, `requireShare`, `requireMember/Leader`)
- 멤버십은 `(team_id, user_id)` 유니크 인덱스로 1회 조회 (v1: 팀 멤버 전체를 로드해 순회)
- 읽을 수 없는 대상은 404 — 다른 사람의 파일 ID 가 존재하는지조차 알 수 없게
- 폴더 조회 응답에 "이 폴더에서의 내 권한"을 함께 내려 화면이 버튼을 권한에 맞게 표시

## 결과
- 권한 규칙 변경이 한 파일에서 끝나고, `AccessControlTest` 가 v1 의 누락 경로를 모두 회귀 검사
