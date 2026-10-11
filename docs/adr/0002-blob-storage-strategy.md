# ADR-0002 파일 저장소 추상화와 트랜잭션 연동

## 배경
v1 은 서비스가 Azure SDK(`BlobContainerClient`)를 직접 사용했습니다. 설정에 `mode: local` 이 있었지만 구현이 없어 Azure 계정 없이는 실행·테스트가 불가능했고, 파일 삭제는 DB 트랜잭션 도중 Blob 을 먼저 지워 이후 DB 오류가 나면 복구할 수 없었습니다.

## 결정
- `BlobStorage` 인터페이스(put·open·copy·delete·readOnlyUrl) + `LocalBlobStorage` / `AzureBlobStorage` (설정 `STORAGE_TYPE` 으로 선택)
- 저장소 키는 서버가 만든 UUID 만 사용 (`files/…`, `versions/…`)
- `BlobLifecycle`:
  - 쓰기: 저장 후 트랜잭션이 **롤백되면** 방금 쓴 blob 삭제 — 확실히 롤백된 경우(작업 안의 실패)만. 커밋 단계의 실패는 커밋됐는지 알 수 없어
    지우지 않고 고아 파일 정리에 맡깁니다 [H-4, 아래 결과]
  - 삭제: 트랜잭션이 **커밋된 뒤에만** blob 삭제
- 저장하면서 `HashingInputStream` 으로 크기·SHA-256 을 한 번에 계산 (서명 무결성에 사용)
- Azure SDK 의 기본 HTTP 클라이언트(Netty 4.1)를 JDK HttpClient 로 교체 — Spring Boot 4 의 Netty 4.2 와의 충돌을 원천 차단

## 결과
- Docker 만으로 로컬 실행·E2E 가능, Azure 구현은 Azurite 에뮬레이터로 테스트
- DB 와 저장소 사이 불일치는 "커밋 후 삭제 실패 → 고아 파일"만 남을 수 있음 (데이터 손실 없음)
- (출시 기준 QA 후속) 고아 파일 정리를 구현했습니다 — `OrphanBlobCleaner` 가 매일 05:00 저장소의 `files/`·`versions/` 를 훑어,
  유예 시간(기본 24시간)보다 오래됐고 어떤 버전도 가리키지 않는 파일만 지웁니다. 유예 시간은 아직 DB 에 반영되지 않은 업로드를 지키기
  위한 것이고, 새 버전은 늘 새 키를 쓰므로 오래된 고아가 다시 쓰일 일은 없습니다 [IMP-05].
  QA 에서 폴더 복사 도중 강제 종료로 생긴 고아 150개가 이 정리의 근거입니다([QA_2026-10-03](../QA_2026-10-03.md)).
- (종합 기술 리뷰 후속, H-4) 업로드·텍스트 저장·복사는 저장소에 먼저 쓰고 DB 에 기록하는데, 커밋 단계의 예외(커밋은 됐는데 확인 응답을 못 받음 —
  DB 장애 조치·네트워크 끊김)에서도 파일을 지워 "버전은 있는데 내용이 없는" 데이터 손실이 날 수 있었습니다. `TransactionRunner.writeReadCommitted(작업, 롤백 시 정리)` 는
  작업 자체가 실패했을 때만 정리를 부르고, 커밋 단계 실패는 경고 로그만 남깁니다. 정말 롤백됐다면 그 파일은 위의 고아 파일 정리가 지웁니다
  (공간을 하루 더 쓰는 대신 데이터를 잃지 않음). `CommitOutcomeUnknownTest` 가 실제 커밋 뒤 예외를 던지는 트랜잭션 관리자로 확인합니다.
