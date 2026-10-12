# 5차 점검 원자료 (REVIEW_2026-10-12b)

계획·결과는 [docs/REVIEW_2026-10-12b.md](../../../docs/REVIEW_2026-10-12b.md), 가상 스레드 결정은 [ADR-0014](../../../docs/adr/0014-platform-threads-on-java21.md).

| 파일 | 내용 |
|---|---|
| `c1-one-cpu-dump-excerpt.txt` | C-1 재현: `-XX:ActiveProcessorCount=1` + 실제 Azure Blob(시험용 컨테이너) + 가상 스레드 켬에서 30MB 1건을 내려받자 10분 넘게 무응답. 그때의 스레드 덤프 발췌(`StorageInputStream.read` 안에서 `parkOnCarrierThread`) |
| `vt-{true,false}-r{1..5}/k6-*-50.json` | 가상 스레드 켬·끔 비교. QA 스택(Docker 4 CPU)에서 같은 이미지로 설정만 바꿔 k6 MIX(api·read·write) 50명 60초를 5회 번갈아 잼 |
| `vt-compare-summary.txt` | 위 결과의 회차별 짝 비교(처리량·p95·실패율)와 짝 비율 중앙값 |
| `vt-true-repeat-6rounds.txt` | 4회차 켬에서 본 멈춤을 다시 보려고 켬으로만 6회 더 잰 결과(다시 나오지 않음) |
| `upload-burst-before.json` · `upload-burst-after.json` | M-9: 파일 100개를 한 번에 올리는 동안 화면이 보낸 다시 받기 요청(GET) 수 — 200건 → 14건. 도구 `qa/scripts/upload-burst-requests.mjs` |
| `blob-unresponsive-after.txt` | M-3: 연결은 받고 응답하지 않는 저장소에 대한 업로드가 끝나기까지 걸린 시간(기본 설정). 수정 전 측정은 `../deploy-2026-10-12/blob-unresponsive.txt`(284.3초) |

측정 환경: MacBook(Apple Silicon) · Docker Desktop VM 4 CPU. 같은 맥에서 다른 프로그램(앱 화면 렌더러 약 200% CPU)이 돌아 k6 처리량이 회차마다
2~3배 흔들렸습니다. 그래서 같은 회차 안의 짝 비교로만 봅니다(QA_2026-10-03 §9-6 의 원칙).
