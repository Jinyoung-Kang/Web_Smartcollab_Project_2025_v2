# ADR-0007 커밋 후 이벤트로 실시간 반영

## 배경
v1 은 알림을 10초마다 폴링하고, 팀마다 WebSocket 연결을 따로 열었으며, 다른 사람이 파일을 올려도 새로고침해야 보였습니다.

## 결정
- 서비스는 `ApplicationEventPublisher` 로 도메인 이벤트(FolderChanged, TeamChanged, ChatPosted, UserNotified)만 발행
- `RealtimePublisher` 가 `@TransactionalEventListener(AFTER_COMMIT)` 로 STOMP 목적지에 전달 — 롤백된 변경은 절대 나가지 않음
- 이벤트에는 데이터가 아니라 "무엇이 바뀌었는지(폴더 ID 등)"만 싣고, 클라이언트는 해당 쿼리를 무효화해 REST 로 다시 조회 → 권한 검사 경로가 하나
- 클라이언트는 사용자당 STOMP 연결 1개에 팀별 구독을 여럿 둠, 재연결 시 구독 복원

## 결과
- 폴더 변경·권한 변경·팀 삭제·알림이 즉시 반영 (E2E 로 두 사용자 시나리오 검증)
- 대가: SimpleBroker 는 인메모리라 다중 인스턴스에서는 외부 브로커 필요
