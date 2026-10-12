# Azure 첫 배포 점검 — 기동 중 멈춤·DB 연결 실패 (DEP-01·DEP-02)

환경: App Service Linux B1(1코어·1.75GB, Java SE 21 런타임, jar 배포) · Azure Database for MySQL Flexible B1ms(8.4) · Blob Storage(LRS), Korea Central.
IP·리소스 이름은 가렸습니다(`<ip>`·`<db>`·`<storage>`).

## 관찰 (`azure-app-log-excerpt.txt`)

| 시각(UTC) | 일 |
|---|---|
| 04:32:32 | 첫 기동 완료(173.3초). 기동 시드(ApplicationReadyEvent, main 스레드) 시작 |
| 04:32:58 | App Service 가 이전 컨테이너(파킹 페이지)에 SIGTERM |
| 04:32:59 | 시드가 demo3 의 첫 표본 파일 저장 직후 멈춤. readiness 503(OUT_OF_SERVICE) |
| 04:35:20 | DB: 열린 InnoDB 트랜잭션 없음, 실행 중인 쿼리 없음. DB 의 파일 버전 수(44) = Blob 수(44) — 다음 파일은 저장소에 닿지 않음 |
| ~04:43 | 10분 넘게 시드 실패 로그 없음(같은 컨테이너에서 다른 요청·업로드는 정상) |
| 04:48:09 | 설정 변경으로 띄운 다른 기동이 Flyway 첫 연결에서 `Connection attempt exceeded defined timeout` → 기동 실패, App Service 가 다시 띄워 04:52:48 정상 |

기동 시간(같은 B1): 173.3 · 210.0 · 147.1 · 163.9초.

## 원인 좁히기

| 가설 | 측정 | 결과 |
|---|---|---|
| Blob 요청이 응답 없이 멈춤 | 응답하지 않는 서버에 운영과 같은 방식으로 만든 클라이언트로 업로드: HTTP 284.3초(연결 4회, `HttpTimeoutException`), HTTPS(핸드셰이크 멈춤) 84.3초(`HttpConnectTimeoutException`) | 5분 안에 실패 — 10분 넘는 멈춤을 설명 못 함. SDK 기본값(설치된 jar 바이트코드): 연결 10초·쓰기/응답/읽기 60초, 재시도 4회·시도당 상한 없음(`Integer.MAX_VALUE`초)·대기 4초부터 최대 120초 |
| 1코어 + 가상 스레드 굶음(azure-core 의 HTTP 실행기가 Java 21 에서 가상 스레드) | 로컬에서 `-XX:ActiveProcessorCount=1` + 실제 Blob(시험용 컨테이너) + 체험 초기화 20초마다 반복, 약 21분 | 64회 모두 완료, 오류 0 — 재현 안 됨 |
| **쿼리를 보낸 뒤 DB 연결이 소리 없이 끊김** | 시험 DB 앞 중계기를 쿼리 직전에 얼림(바이트를 버림, RST 없음), 쿼리 시간 제한 2초 | **581초가 지나도 `NioSocketImpl.read` 에서 대기**(`db-blackhole-thread-dump.txt`). 연결을 닫으려던 스레드도 그 연결의 잠금을 기다리며 멈춤. DB 에는 쿼리가 닿지 않았으므로 트랜잭션도 없음 — 관찰과 같음 |

트랜잭션 시간 제한(30초, IMP-08)은 DB 에 `KILL QUERY` 를 보내는 방식이라, DB 가 받지 못한 쿼리는 풀지 못합니다. Connector/J 의 `socketTimeout` 기본값은 0(무한)입니다.
두 장애 모두 App Service 가 컨테이너를 바꾸는 시점에 일어났습니다. 네트워크가 왜 끊겼는지는 플랫폼 쪽이라 확인하지 못했습니다.

## 조치와 시험

- DEP-01: `socketTimeout` 60초(`DB_SOCKET_TIMEOUT_MS`) — 끊긴 연결의 쿼리가 그 시간에 실패합니다.
- DEP-02: Flyway `connect-retries` 5회(`DB_CONNECT_RETRIES`) — 기동할 때 첫 연결이 실패해도 1·2·4·8·16초 간격으로 다시 시도합니다.
- `DatabaseNetworkFailureTest`: 수정 전 2건 실패(`tests-before-fix.txt`), 수정 뒤 4건 통과(`tests-after-fix.txt`).
