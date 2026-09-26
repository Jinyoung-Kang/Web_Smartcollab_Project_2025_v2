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
| JavaScript 전송량 | 1,156 KB | **133 KB** | -88% |
| 메인 스레드 스크립트 실행 시간 (CDP `ScriptDuration`) | 566 ms | **33 ms** | -94%, 네트워크와 무관 |
| 전체 전송량 | 1,288 KB | 496 KB | v2 는 한글 웹폰트 334KB 포함 (v1 은 시스템 폰트) |
| 요청 수 | 20 | 21 | v2 는 글자 범위별 폰트 조각을 받음 |
| 로그인 화면이 보이기까지 | 1,132 ms | 205 ms | 참고용 — v1 은 인터넷 CDN, v2 는 로컬 서버 |

v1 이 느렸던 이유: React **개발용** 빌드 + 브라우저에서 JSX 를 변환하는 Babel Standalone(약 3MB 원본) + Tailwind Play CDN(런타임 CSS 생성). v2 는 Vite 로 미리 빌드·압축하고, 휴지통·검색·편집기·공유 페이지는 필요할 때 내려받도록 나눴습니다(코드 분할).

측정 중 발견한 설정 결함: Spring Boot 응답 압축 대상에 `text/javascript` 가 빠져 JS 가 **압축 없이** 전송되고 있었습니다(418KB → 수정 후 133KB). `application.yml` 에 추가했습니다.

원자료: [measurements/initial-load.json](measurements/initial-load.json)

## 4. 구조적 개선 (측정 대신 설계로 보장)

| 항목 | v1 | v2 |
|---|---|---|
| WebSocket 연결 | 팀마다 1개 | 사용자당 1개, 구독만 추가 |
| 알림 | 10초 폴링 | WebSocket 즉시 전달 (+ 2분 주기 보정 조회) |
| 채팅 기록 | 전체 조회 | 커서 페이지 30개, `(team_id, message_id)` 인덱스 |
| 정적 리소스 캐시 | 없음 | 해시 파일명 1년 `immutable`, `index.html` 은 `no-cache` |
| 삭제 | 폴더마다 재귀 호출·개별 DELETE | 재귀 CTE 로 대상 수집 후 청크(500) 단위 일괄 DELETE |
