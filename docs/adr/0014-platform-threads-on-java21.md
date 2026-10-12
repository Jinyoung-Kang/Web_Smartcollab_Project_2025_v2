# ADR-0014 Java 21 에서는 요청 처리에 가상 스레드를 쓰지 않음

## 배경
`spring.threads.virtual.enabled=true` 로 Tomcat 요청·STOMP·예약 작업이 가상 스레드에서 돌았습니다. 켠 이유를 적은 문서는 없습니다.
Azure App Service B1(1 vCPU) 배포에서 큰 파일 한 건을 내려받자 서버 전체가 영구히 멈췄습니다(5차 점검 C-1).

- Java 21 가상 스레드는 `synchronized` 안에서 기다리면 캐리어 스레드에 묶입니다(pinning).
- Azure SDK 의 `BlobInputStream.read`(`StorageInputStream`)는 `synchronized` 안에서 동기 다운로드를 기다리고, 업로드도 비슷한 경로를 탑니다.
- 그런데 SDK 의 HTTP 응답 처리(JDK HttpClient 의 TLS 처리 포함)는 azure-core 의 공유 실행기에서 돕니다. 이 실행기의 기본값이 가상 스레드입니다.
- JVM 이 CPU 를 1개로 보면 캐리어도 1개라, 묶인 요청이 응답 처리를 기다리는 동안 그 처리를 돌릴 캐리어가 없습니다. 헬스 체크를 포함한 모든 요청이 멈춥니다.

재현과 측정은 `qa/results/review-2026-10-12b` 에 있습니다.
- 로컬 `-XX:ActiveProcessorCount=1` + 실제 Blob 에서 30MB 1건 → 10분 넘게 무응답(덤프의 `parkOnCarrierThread`)
- 시험: `SingleCarrierDownloadTest`(HTTPS Azurite)
- 평문 HTTP 의 Azurite 나 코어가 많은 개발 PC·CI 에서는 드러나지 않았습니다.

## 선택지
1. **요청 처리를 플랫폼 스레드로**(`spring.threads.virtual.enabled=false`)
2. Blob 읽기를 `synchronized` 없는 자체 범위 스트림으로 바꾸고 SDK 실행기를 플랫폼 스레드로 — 이 경로는 고쳐지지만, 다른 의존성(드라이버·파서·SDK)의 같은 위험은 남습니다.
3. Java 24 이상(JEP 491 이 `synchronized` 고정을 없앰) — App Service 의 Java 런타임은 21 까지라 컨테이너 배포로 바꿔야 합니다.

## 결정
1번. 기본값을 끄고, 운영과 같은 조건(JVM 이 CPU 를 1개로 봄)에서만 도는 시험 작업 `singleCarrierTest` 를 `check` 에 넣습니다.

- 커밋 뒤 저장소 삭제(P-02)는 따로 만든 가상 스레드 실행기를 그대로 씁니다. 이 경로에는 `synchronized` 대기가 없고, 같은 시험이 CPU 1개에서 삭제가 끝나는 것을 확인합니다.
- Java 24 이상으로 옮기면 2·3번과 함께 다시 켜는 것을 검토합니다. 그때도 `singleCarrierTest` 를 통과해야 합니다.

## 결과
- 요청 처리가 Tomcat 의 플랫폼 스레드 풀(기본 최대 200)에서 돕니다. 같은 QA 스택에서 켬/끔을 번갈아 잰 처리량·지연은 아래 "측정"에 있습니다.
- 운영에는 코드 기본값이 바뀌기 전에 앱 설정 `SPRING_THREADS_VIRTUAL_ENABLED=false` 로 먼저 적용했습니다. 30MB 내려받기 1건·2건 동시가 6.7~8.4초에 정상이고, 그동안 헬스 체크 중앙값은 175ms 였습니다. 코드가 배포되면 이 앱 설정은 지웁니다.

## 측정
QA 스택(Docker 4 CPU, `qa/compose.qa.yml`)에서 같은 이미지로 켬·끔만 바꿔 k6 MIX 50명 60초를 5회 번갈아 쟀습니다(1~2회는 켬→끔, 3~5회는 끔→켬).
원자료는 `qa/results/review-2026-10-12b/vt-{true,false}-r{1..5}`, 요약은 `vt-compare-summary.txt` 입니다.

- 같은 맥의 다른 부하(앱 화면 렌더러 약 200% CPU) 때문에 회차마다 처리량이 2~3배 흔들려, 같은 회차 안의 짝 비율(끔/켬)로 봅니다.
- 짝 비율 중앙값(처리량): api 1.17 · read 1.12 · write 1.15 — 끄면 처리량이 줄지 않았습니다(회차별 범위 0.57~1.80 으로 흔들림이 큼).
- 4회차 켬은 멈췄습니다: api 1.8 rps(실패 32%), read·write 실패 100%·p95 60초(시간 초과). 같은 회차의 끔은 정상이었습니다.
- 이 멈춤은 바로 이어 켬으로만 6회 더 잰 실행(`vt-true-repeat-6rounds.txt`)에서는 다시 나오지 않았습니다(한 번 write 실패 12.67%).
  로그를 남기지 못해 원인은 확인하지 못했습니다 — Blob 없는 로컬 저장소 구성이라 C-1 과 같은 경로라고 단정할 수 없습니다.

정리하면, 이 측정 범위에서 가상 스레드를 꺼서 잃은 처리량은 보이지 않았고, 켠 쪽에서만 원인 모를 멈춤이 한 번 있었습니다.
