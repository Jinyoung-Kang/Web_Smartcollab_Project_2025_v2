# 출시 기준 QA — 점검 스크립트와 원자료

방법·결함·판단은 [docs/QA_2026-10-03.md](../docs/QA_2026-10-03.md) 에 있습니다. 여기 있는 것은 그 근거가 되는 점검 도구와 측정 원자료입니다.
모든 스크립트는 **로컬 QA 스택에만** 씁니다. `lib.mjs` 는 대상 주소가 localhost 가 아니면 멈춥니다.

## QA 스택

```bash
APP_PORT=8080 DB_PORT=13307 docker compose -f docker-compose.yml -f qa/compose.qa.yml -p sc-qa up -d --wait
# 끝나면
docker compose -p sc-qa down -v
```

[compose.qa.yml](compose.qa.yml) 이 기본 compose 에 더하는 것:

| 구성 | 용도 |
|---|---|
| Toxiproxy(DB 앞, API 18474) | DB 지연·끊김 주입 |
| DeepL 목([deepl-mock.mjs](deepl-mock.mjs), 19000) | 실제 DeepL 대신 지연·5xx·끊김 |
| 느린 쿼리 로그 | 50ms 이상·인덱스 없는 쿼리 기록 |
| 앱 메모리 한도 2GB | 운영과 같은 조건 |
| 요청 제한 완화 | 시험 사용자를 많이 만들기 위해(제한 자체는 백엔드 시험이 검증) |

## 스크립트 (`node qa/scripts/<이름>.mjs`, 저장소 루트에서)

| 영역 | 스크립트 |
|---|---|
| 보안 | `authz-matrix`(인가 행렬), `auth-session`(탈퇴·변조 토큰, 쿠키, 로그인 오류), `csrf-headers`, `api-fuzz`(입력 퍼징), `file-handling`(파일명·미리보기 헤더), `multipart-edge`, `osv-check.py` |
| 신뢰성 | `reliability <시나리오…>`(db-cut·db-latency·kill-upload·kill-copy·storage-readonly·missing-blob·deepl·graceful-upload), `graceful-shutdown`, `concurrency`, `repro-leaderless-team`·`leaderless-impact`, `integrity-check.sh`(DB ↔ 저장소 정합성), `backup-restore.sh`(+ `backup-seed.mjs`, 임시 스택 sc-bk) |
| 기능 | `boundary`(경계값) |
| 성능 | `perf-seed`(부하 시드), `run-load.sh [docker]`(k6 [load.js](k6/load.js) + 느린 쿼리 요약 `slowlog.py`), `large-scope`, `lighthouse.sh` |
| 화면·접근성 | `e2e/qa`(`cd e2e && npx playwright test -c playwright.qa.config.ts`): 크롤·키보드·주요 흐름·중복 요청·접근성 확장 점검 |

## 원자료 (results/)

| 위치 | 내용 |
|---|---|
| `before-fix/` | 수정 전 이미지에서 잰 결과. 결함의 증거 |
| 최상위 | 수정 뒤 다시 돌린 결과. 단, `lighthouse-summary.json`·`large-scope.json`·`perf-users.json` 은 수정 전 스택에서 잼 |
| `failing-tests-before-fix.txt` · `tests-after-fix.txt` | 결함 재현 시험의 수정 전 실패·수정 뒤 통과 |
| `independent-review.md` | 독립 검토(별도 에이전트)의 재현·심각도 확인 |
| `docker/` · `after-fix/` | k6(Docker 네트워크 안) 수정 전 / 수정 뒤 쓰기 |
| `host/` · `docker-run1-oom/` | 비교에 쓰지 않은 측정(호스트 포트 포워딩 병목 / 메모리 한도 없이 OOMKilled). 기록으로만 둠 |
| `backup-restore/` | 백업·복원 전후 비교 |
| `screens/` | 화면 크롤 스크린샷(수정 전) |

느린 쿼리 원본 로그(`*.log`)는 크기 때문에 저장소에 넣지 않습니다(`.gitignore`). 요약은 `slowlog-load.json` 에 있습니다.
