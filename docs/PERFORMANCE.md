# 성능 측정

추정치가 아니라 이 저장소의 스크립트·테스트로 측정한 값만 적습니다. 원자료는 [measurements/](measurements/) 에 있습니다.

## 1. DB 쿼리 수 — N+1 제거

**측정**: `backend/src/test/java/com/smartcollab/perf/QueryCountBenchmarkTest.java`
실제 MySQL 8.4(Testcontainers)에 루트 아래 3단계, 총 62개 폴더와 팀 5개를 만들고, Hibernate 통계의 "준비된 SQL 수"를 셉니다.
v1 방식은 v1 코드의 조회 패턴(폴더마다 하위 목록·파일을 따로 조회 — 지연 로딩과 같은 SQL 발생 패턴)을 같은 DB 에서 재현했습니다.

| 기능 | v1 방식 | v2 | v2 방법 |
|---|---:|---:|---|
| 폴더 트리 | 63 | **1** | 스코프(개인/팀)의 폴더를 한 번에 읽고 해시맵으로 O(n) 트리 구성 (`FolderTree`) |
| 파일 이름 검색 | 125 | **2** | 폴더 목록 1회 + `folder_id IN (…) AND name LIKE` 1회 |
| 내 팀 목록 | (팀 수에 비례) | **3** | 소속·팀장(fetch join) / 인원 수(GROUP BY) / 루트 폴더(IN) |
| 폴더 내용 + 경로(깊이 3) | (깊이에 비례) | **4** | 경로는 재귀 CTE 1회 (`WITH RECURSIVE ancestors`) |

원자료: [measurements/query-counts.json](measurements/query-counts.json)

## 2. 다운로드 메모리 — 스트리밍

**측정**: `backend/src/test/java/com/smartcollab/perf/DownloadMemoryBenchmarkTest.java`
32MB 무작위 파일을 한 번 내려받는 동안 요청 스레드가 힙에 할당한 바이트 (`ThreadMXBean.getThreadAllocatedBytes`, JIT 워밍업 3회 후).

| 방식 | 할당량 |
|---|---:|
| v1: `ByteArrayOutputStream` 으로 전부 받은 뒤 `toByteArray()` 로 한 번 더 복사 | **100,664,440 B (≈ 96 MiB, 파일의 3배)** |
| v2: 저장소 스트림 → 응답 스트림 (고정 크기 버퍼) | **17,248 B** |

v1 은 업로드 한도가 800MB 여서, 큰 파일 몇 개를 동시에 내려받으면 서버 메모리가 바닥날 수 있는 구조였습니다.

원자료: [measurements/download-memory.json](measurements/download-memory.json)

## 3. 프론트엔드 초기 로딩

**측정**: `e2e/scripts/measure-initial-load.mjs`
- v1: 태그 `v1.0.0` 의 정적 파일을 로컬 서버로 제공(React·Babel·Tailwind 등은 v1 그대로 외부 CDN).
- v2: Docker 이미지(Spring Boot 가 빌드된 SPA 제공, gzip).
- Chrome, 캐시 없는 새 컨텍스트, 로그인 버튼이 보일 때까지. 5회 중앙값.

| 지표 | v1 | v2 | 비고 |
|---|---:|---:|---|
| JavaScript 전송량 | 1,156 KB | **153 KB** | -87% |
| 메인 스레드 스크립트 실행 시간 (CDP `ScriptDuration`) | 540 ms | **35 ms** | -94%, 네트워크와 무관 |
| 전체 전송량 | 1,288 KB | 516 KB | v2 는 한글 웹폰트 334KB 포함 (v1 은 시스템 폰트) |
| 요청 수 | 20 | 21 | v2 는 글자 범위별 폰트 조각을 받음 |
| 로그인 화면이 보이기까지 | 1,059 ms | 150 ms | 참고용 — v1 은 인터넷 CDN, v2 는 로컬 서버 |

v1 이 느렸던 이유: React **개발용** 빌드 + 브라우저에서 JSX 를 변환하는 Babel Standalone(약 3MB 원본) + Tailwind Play CDN(런타임 CSS 생성). v2 는 Vite 로 미리 빌드·압축하고, 휴지통·검색·편집기·공유 페이지는 필요할 때 내려받도록 나눴습니다(코드 분할).

측정 중 발견한 설정 결함: Spring Boot 응답 압축 대상에 `text/javascript` 가 빠져 JS 가 **압축 없이** 전송되고 있었습니다(당시 418KB → 수정 후 133KB). `application.yml` 에 추가했습니다.

1차 코드 리뷰 후 재측정: 편집기에서 저장하지 않은 내용을 지키는 앱 내 이동 차단(`useBlocker`, [REVIEW_2026-09 BUG-04](REVIEW_2026-09.md))에 데이터 라우터가 필요해, 라우터 엔진이 들어가며 JS 가 133KB → 151KB(gzip +18KB) 늘었습니다. 데이터 손실 방지가 더 중요하다고 판단해 받아들였습니다.

2차 점검 후 재측정(2026-09-28, 위 표): 오류 안내 화면·탭 제목·목록 점진 렌더링·세션 만료 안내 등이 더해져 JS 가 151KB → 153KB(+2KB) 늘었습니다. v1 스크립트 실행 시간은 이번에 5회 529~548ms(중앙값 540ms)로, 이전 표의 578ms(5회 중앙값, 최대 697ms)보다 흩어짐이 작습니다. v2 는 두 번 모두 35ms 입니다.

원자료: [measurements/initial-load.json](measurements/initial-load.json)

## 4. 저장소 입출력과 DB 커넥션

**측정**: `backend/src/test/java/com/smartcollab/perf/StorageConnectionBenchmarkTest.java`
저장소 쓰기·복사가 1.5초 걸리도록 감싼 저장소(Azure 네트워크 지연 흉내)로 바꿔 끼우고, 저장소 작업을 시작하는 순간의 활성 DB 커넥션 수(Hikari)와,
풀 크기(10)만큼 업로드가 동시에 저장소에 쓰는 동안 다른 사용자의 폴더 조회 시간을 쟀습니다.

| 지표 | 개선 전 (한 트랜잭션) | 개선 후 (저장소 작업을 트랜잭션 밖으로) |
|---|---:|---:|
| 업로드가 저장소에 쓰는 동안 점유한 커넥션 | 1 | **0** |
| 복사가 저장소에서 복사하는 동안 점유한 커넥션 | 1 | **0** |
| 업로드 10건이 저장소에 쓰는 중 다른 사용자의 폴더 조회 | 1,482 ms | **56 ms** |

개선 전에는 업로드마다 커넥션을 붙잡은 채 저장소 작업을 기다려, 동시 업로드가 풀 크기에 이르면 모든 요청이 저장소 작업이 끝날 때까지 대기했습니다.
개선 후에는 짧은 읽기 트랜잭션(권한 확인) → 트랜잭션 밖 저장소 작업 → 짧은 쓰기 트랜잭션(권한 재확인·저장)으로 나누고, 마지막 단계가 실패하면 써 둔 파일을 지웁니다(`StorageCleanupTest`).
지연 시간 1.5초는 측정용 가정이며, 실제 Azure 지연은 파일 크기와 네트워크에 따라 다릅니다.

원자료: [measurements/storage-connections.json](measurements/storage-connections.json)

## 5. 항목이 많은 폴더 — 화면 렌더링 [PERF-02]

**측정**: `e2e/scripts/measure-large-folder.mjs` — 데모 계정으로 로그인한 뒤 폴더 목록 API 응답만 가짜로 1,000·5,000개로 늘려 화면을 엽니다(Chrome, 1440×900).
서버는 병목이 아니었습니다: 목록 API 가 5,000개에 76~86ms(Testcontainers MySQL, JSON 1.1MB — 전송은 gzip).

| 지표 (5,000개) | 전부 그리기 | 200개씩 나눠 그리기 |
|---|---:|---:|
| 첫 표시 (주소 이동 → 첫 묶음) | 1,454 ms | **172~215 ms** |
| '크기' 정렬 클릭 → 다음 프레임 | 190 ms | **19~33 ms** |
| '모두 선택' 클릭 → 다음 프레임 | 202 ms | **17~54 ms** |
| JS 힙 | 123 MB | **24~37 MB** |

1,000개에서도 첫 표시 410 → 135~214ms, 정렬 99 → 14~24ms. 변경 전 값은 1회, 변경 후는 3회 범위입니다(이전 코드로는 다시 잴 수 없어 원자료에 기록으로 남김).
앞에서부터 200개씩 그리고 목록 끝이 보이면(IntersectionObserver) 또는 "나머지 N개 더 보기"로 이어서 그립니다. 선택·정렬·Ctrl+A 는 그리지 않은 항목까지 포함한 전체 목록 기준입니다.
서버 페이지네이션은 정렬(이름·날짜·올린 사람·크기)을 서버로 옮겨야 하고 이 규모에서는 서버가 병목이 아니어서, 화면에서 해결했습니다.

원자료: [measurements/large-folder.json](measurements/large-folder.json)

## 6. 알림 목록 인덱스 [PERF-04]

**측정**: `NotificationQueryPlanTest` 와 같은 데이터(한 사용자 알림 3,000개 + 다른 사용자 20명 × 200개)에서 "최신순 30개" 조회를 `EXPLAIN ANALYZE` 로 3회.

| | 실행 계획 | 실제 시간 |
|---|---|---:|
| 기존 인덱스 `(user_id, is_read, created_at)` | 사용자 알림 전체를 읽고 정렬 (`Using filesort`) | 2.2~2.5 ms |
| 새 인덱스 `(user_id, created_at, notification_id)` | 인덱스 순서대로 30개만 (`Backward index scan`) | **0.10~0.22 ms** |

알림은 자동으로 지우지 않아 오래 쓸수록 쌓이므로, 정렬 비용이 알림 수에 비례하지 않게 했습니다. 알림이 적을 때는 옵티마이저가 두 인덱스의 비용을 거의 같게 봅니다(200개일 때 35.7 vs 35.8).

## 7. 여러 항목 삭제 — 요청 수 [PERF-03]

선택한 항목마다 삭제 요청·트랜잭션·팀 변경 알림이 하나씩 생기던 것을 `POST /api/items/delete` 한 번으로 바꿨습니다.
브라우저에서 파일 5개를 선택해 지우면 변경 요청 5 → **1**, 같은 폴더의 팀 변경 알림 5 → **1**(`ItemDeletionTest`).

## 8. 구조적 개선 (측정 대신 설계로 보장)

| 항목 | v1 | v2 |
|---|---|---|
| WebSocket 연결 | 팀마다 1개 | 사용자당 1개, 구독만 추가 |
| 알림 | 10초 폴링 | WebSocket 즉시 전달 (+ 2분 주기 보정 조회) |
| 채팅 기록 | 전체 조회 | 커서 페이지 30개, `(team_id, message_id)` 인덱스 |
| 정적 리소스 캐시 | 없음 | 해시 파일명 1년 `immutable`, `index.html` 은 `no-cache` |
| 삭제 | 폴더마다 재귀 호출·개별 DELETE | 재귀 CTE 로 대상 수집 후 청크(500) 단위 일괄 DELETE |
| 휴지통 자동 비우기 | — | 500개씩 따로 커밋 — 잠금을 오래 쥐지 않고, 한 배치가 실패해도 나머지는 진행 [PERF-05] |
