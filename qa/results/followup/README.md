# QA 후속 원자료 (2026-10-03)

[docs/QA_2026-10-03.md §10](../../../docs/QA_2026-10-03.md#10-후속-작업-결과) 의 근거입니다. 측정은 모두 로컬 QA 스택(`sc-qa`, 앱 메모리 한도 2GB, DB 풀 10)에서 했고, Docker VM(CPU 4개)을 다른 프로젝트 컨테이너와 함께 썼습니다.

| 파일 | 내용 |
|---|---|
| `failing-tests-before-fix.txt` · `tests-after-fix.txt` | 항목마다 수정 전에 실패한 시험과 수정 뒤 결과(백엔드 261건·프론트 94건) |
| `ops-db-check.txt` | 운영 DB 점검(읽기 전용) — 상시 로컬 스택의 팀장·초대 정합성, V4 적용 뒤 집계 비교 |
| `perf-users.json` | 이번 시드(사용자 60명·팀 6개·큰 폴더 파일 1만 개). QA 고정 비밀번호만 담김 |
| `ab-1-new/` → `ab-2-main/` → `ab-3-new/` | 같은 스택·같은 데이터에서 앱 이미지만 바꿔 차례로 잰 결과(이 브랜치 → `main` 63a5547 → 이 브랜치). 큰 범위(`large-scope.json`)와 k6 쓰기 10·50명. 측정할 때마다 업로드가 쌓여 뒤 측정일수록 데이터가 많습니다 |
| `ab-1-new/load-docker-stats.csv` · `ab-1-new/slowlog-write.json` | 첫 쓰기 부하 동안의 자원 사용(5초마다)과 느린 쿼리 요약 |
| `ab-slowlog-all.json` | 세 측정 전체의 느린 쿼리 요약 — 저장 한도 합산 쿼리는 `main` 이미지 구간에만 있음 |
| `reconcile-before.json` · `reconcile-log.txt` · `reconcile-after.json` | `main` 이미지가 쓴 뒤 집계가 어긋난 사용자 50명 → 정리 작업(1분마다로 바꿔 실행)이 바로잡은 기록 → 어긋남 0 |
| `reliability-db-cut_db-latency.json` | DB 끊김(503·readiness)과 왕복마다 1초 지연 |
| `reliability-kill-copy-c500.json` · `orphan-cleanup-log.txt` · `orphan-cleanup-after.json` | 복사 도중 강제 종료로 생긴 고아 133개 → 고아 정리(유예 1분·1분마다로 바꿔 실행)가 지운 기록 → 고아 0·유실 0 |
| `duplicate-requests.json` | 내 변경 뒤 같은 목록을 다시 부른 횟수(팀 폴더 2 → 1) |
| `lighthouse-summary.json` · `lighthouse-summary-run2.json` | Lighthouse 13.5 두 번(각 화면·기기 3회 중앙값) |
