# 배포 가이드

## 1. 로컬 (Docker Compose)

```bash
cp .env.example .env         # JWT_SECRET 등 값 입력 (openssl rand -base64 48)
docker compose up -d --build --wait
open http://localhost:8080
```

앱 컨테이너의 메모리 한도는 `APP_MEM_LIMIT`(기본 `2g`)이고, JVM 은 그 75% 를 힙으로 씁니다. 한도가 없으면 JVM 이 Docker 호스트 메모리의
75% 를 힙으로 잡아, 다른 컨테이너와 메모리를 나누는 호스트에서는 부하 중 OOMKilled(137)로 죽었습니다(출시 기준 QA, IMP-06).
2GB 한도에서는 같은 부하에 JVM 메모리가 최대 1,150MiB 였습니다.

아래 §2 의 설정은 `.env` 에 넣으면 앱 컨테이너에 그대로 전달됩니다(없으면 기본값). 예전에는 `docker-compose.yml` 에 적힌 몇 가지만
전달돼, 나머지(`AI_TIMEOUT`·`DB_POOL_SIZE` 등)는 `.env` 에 넣어도 무시됐습니다 [L-6].

`DEMO_ENABLED=true` 와 `DEMO_PASSWORD` 를 넣으면 체험용 계정(demo1~3)·팀·문서가 만들어집니다.

> 체험 모드에서는 `demo1~3` 계정과 그 데이터가 매일 초기화됩니다. 이 아이디는 가입에 쓸 수 없지만(2026-10 이후), 그 전에 같은 아이디로 가입한 실사용자가 있는 DB 라면 체험 모드를 켜기 전에 확인하세요.

## 2. 환경 변수

| 변수 | 필수 | 기본값 | 설명 |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | 운영 | – | `prod` 이면 Azure 저장소·보안 쿠키·Swagger 비활성 |
| `DB_URL` | ✔ | 개발 `jdbc:mysql://localhost:3306/smartcollab…` / `prod` 는 없음 | MySQL 8 JDBC URL (`serverTimezone=UTC` 권장) |
| `DB_USERNAME` / `DB_PASSWORD` | ✔ | 개발 `smartcollab` / `prod` 는 없음 | DB 계정. `prod` 에서 셋 중 하나라도 없으면 DB 에 접속하기 전에 기동을 멈춥니다 |
| `JWT_SECRET` | ✔(운영) | 개발 시 임시 키 | 32바이트 이상 무작위 문자열 |
| `JWT_TTL` | | `8h` | 로그인 유지 시간 |
| `DB_POOL_SIZE` | | `10` (`prod` 는 `20`) | DB 커넥션 풀 크기 |
| `DB_CONNECTION_TIMEOUT_MS` | | `5000` | DB 커넥션을 기다리는 최대 시간(ms). DB 장애 중 요청을 오래 붙잡지 않도록 짧게 둡니다. 넘으면 503 `SERVICE_UNAVAILABLE` |
| `DB_SOCKET_TIMEOUT_MS` | | `60000` | 쿼리를 보낸 뒤 응답을 기다리는 최대 시간(ms). 연결이 소리 없이 끊겨도(응답도 RST 도 없음) 그 요청이 영원히 멈추지 않게 합니다 — 트랜잭션 시간 제한은 DB 가 받지 못한 쿼리를 풀지 못합니다. `DB_TX_TIMEOUT` 보다 길게, 그보다 오래 걸리는 마이그레이션이 있으면 그때만 늘리세요 [DEP-01] |
| `DB_CONNECT_RETRIES` | | `5` | 기동할 때 첫 DB 연결이 실패하면 다시 시도할 횟수(1·2·4·8·16초 간격, 최대 약 1분). 0 이면 한 번 실패로 기동이 멈춥니다 [DEP-02] |
| `DB_TX_TIMEOUT` | | `30s` | 트랜잭션 하나의 최대 시간. 넘으면 진행 중인 쿼리를 끊고 롤백한 뒤 503 — DB 가 느릴 때 요청이 끝없이 쌓이지 않게 합니다 |
| `STORAGE_TYPE` | | `local` (`prod` 는 `azure`) | `local` \| `azure` |
| `STORAGE_LOCAL_ROOT` | | `~/smartcollab-data` | 로컬 저장소 경로 |
| `AZURE_STORAGE_CONNECTION_STRING` | azure 시 ✔ | – | Blob Storage 연결 문자열 |
| `AZURE_STORAGE_CONTAINER` | | `smartcollab-files` | 컨테이너 이름 (없으면 생성) |
| `COOKIE_SECURE` | | `false` (`prod` 는 `true`) | HTTPS 에서만 쿠키 전송 |
| `CORS_ALLOWED_ORIGINS` | | 개발 `http://localhost:5173,…` / `prod` 는 비어 있음 | 다른 출처에서 API 를 부를 때만 |
| `UPLOAD_MAX_FILE_SIZE` / `UPLOAD_MAX_REQUEST_SIZE` | | `200MB` / `210MB` | 파일 하나의 업로드 한도 / 업로드 요청 전체(파일 + 양식) 한도. 파일 한도를 올리면 요청 한도도 그보다 크게 |
| `MAX_REQUEST_BODY_SIZE` | | `6MB` | 업로드를 뺀 요청 본문(JSON) 한도. `TEXT_EDIT_MAX_BYTES` 를 올리면 그 2배 이상으로 함께 올리세요 |
| `TRASH_RETENTION_DAYS` | | `30` | 휴지통 보관 기간 |
| `QUOTA_PERSONAL` / `QUOTA_TEAM` | | `2GB` / `5GB` | 저장 공간 한도 (옛 버전·휴지통 포함). 계정마다 저장하지 않고 이 값을 읽으므로 바꾸면 기존 계정에도 적용. 비용은 §6 |
| `MAX_TEAMS_PER_USER` | | `6` | 한 계정이 속할 수 있는 팀 수(팀장·멤버 포함). 팀 만들기·초대·수락에서 확인. 낮추기 전부터 더 많은 팀에 속한 계정은 그대로 두고 새로 들어가지만 못함 |
| `MAX_ACCOUNTS` | | `20` | 가입할 수 있는 계정 수(체험 계정 포함, 관리·시스템 계정 제외). 꽉 차면 가입 409, 탈퇴·관리자 삭제로 자리가 남. 체험 모드면 체험 계정 3자리를 늘 남겨 둠. 동시 가입은 서버 안 잠금으로 줄 세우므로 인스턴스 1개 전제(ADR-0018) |
| `MAX_FOLDER_DEPTH` / `MAX_COPY_FOLDERS` | | `50` / `1000` | 폴더 깊이(최상위 아래 단계) / 한 번에 복사하는 폴더 수 |
| `SIGNUP_RATE_PER_HOUR` | | `5` | IP 당 시간당 가입 횟수 |
| `DEMO_QUOTA` | | `2GB` | 체험 계정·체험 팀의 저장 한도 (매일 초기화) |
| `DEMO_SAMPLE_FILES` | | `true` | 체험 계정마다 내 드라이브에 '파일 유형별 시험' 폴더(텍스트·PDF·Office·이미지·기타 표본 16개, 약 190KB)를 만듦. 초기화 때도 다시 만듦 |
| `DEMO_RESET_CRON` / `DEMO_RESET_ZONE` | | `0 0 5 * * *` / `Asia/Seoul` | 체험 데이터 초기화 시각 |
| `TRASH_PURGE_CRON` / `TRASH_PURGE_ZONE` | | `0 0 4 * * *` / `Asia/Seoul` | 휴지통 자동 비우기 시각과 그 기준 시간대 (아래 두 작업도 이 시간대) |
| `USAGE_RECONCILE_CRON` | | `0 30 4 * * *` | 저장 사용량 집계를 실제 버전 합계와 맞춰 보는 시각. 어긋난 저장 공간이 있으면 바로잡고 경고 로그 |
| `ORPHAN_CLEANUP_CRON` / `ORPHAN_GRACE` | | `0 0 5 * * *` / `24h` | 어떤 버전도 가리키지 않는 저장소 파일 정리 시각 / 이보다 새 파일은 올라오는 중일 수 있어 남김 |
| `LOGIN_RATE_PER_MINUTE` / `LOGIN_ACCOUNT_RATE` | | `10` / `20` | 로그인 시도 한도 (IP 당 분당 / 계정당 10분) |
| `SHARE_PASSWORD_RATE` / `SHARE_PASSWORD_LINK_RATE` | | `10` / `50` | 공유 비밀번호 시도 한도 (링크+IP 당 / 링크당, 10분) |
| `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` | | Tomcat 기본값(사설·루프백 대역) | X-Forwarded-For 를 믿을 프록시 주소(정규식). 프록시 없이 직접 노출할 때는 좁히세요 |
| `ANTHROPIC_API_KEY` | | – | Claude 문서 요약·번역(텍스트·PDF·Word). 없으면 추출 요약·DeepL 번역(텍스트)으로 동작. 문서 내용이 Anthropic 으로 전송됩니다 (ADR-0011) |
| `AI_MODEL` | | `claude-haiku-5-5` | 요약·번역에 쓰는 Claude 모델 |
| `AI_SUMMARY_EFFORT` / `AI_TRANSLATION_EFFORT` | | `low` / `medium` | 작업별 생각의 양(low·medium·high·xhigh·max, 잘못된 값이면 기동 거절). 실측으로 정한 값 — 모델을 바꾸면 `qa/scripts/ai-live.mjs` 로 다시 확인하세요 |
| `AI_TOKENS_PER_USER_PER_DAY` | | `300000` | 사용자별 24시간 AI 토큰(입력 + 출력). 호출 전에 예상분을 예약하고 실제 사용량으로 정산합니다 |
| `AI_MAX_DOCUMENT_SIZE` / `AI_TIMEOUT` | | `10MB` / `120s` | 요약·번역할 PDF·Word 크기 상한(텍스트는 편집기 한도) / 요약·토큰 세기 요청 하나의 시간 상한이자 응답이 멈춰도 기다리는 최대 간격 |
| `AI_TRANSLATION_TIMEOUT` | | `10m` | 번역(스트리밍) 하나의 전체 시간 상한 — 긴 번역(출력 최대 64,000 토큰)을 받을 수 있게 따로 둡니다 [H-2] |
| `AI_MAX_CONCURRENT` | | `8` | 서버 전체에서 동시에 돌리는 요약·번역 수(넘으면 503, 사용자당 2건을 넘으면 429). 2GB 컨테이너 기준 값 — 메모리 한도를 바꾸면 함께 조정 [M-1] |
| `DEEPL_API_KEY` / `DEEPL_API_URL` | | – / `https://api-free.deepl.com` | AI 키가 없을 때의 텍스트 번역 (둘 다 없으면 번역 버튼 비활성) / 유료 요금제면 `https://api.deepl.com` |
| `TRANSLATION_CHARS_PER_USER_PER_DAY` | | `100000` | 사용자별 24시간 번역 글자 수. DeepL 요금제의 월 한도에 맞춰 조정하세요 |
| `DEMO_ENABLED` / `DEMO_PASSWORD` | | `false` | 체험 계정·데이터 생성. 비밀번호가 공개 설정 API 로 안내되므로 체험 전용 배포에서만 켜세요 |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD` | | – (끔) | 관리 전용 계정(계정 목록·삭제, ADR-0015). 아이디가 있으면 앱이 켜질 때 계정을 만들고, 비밀번호(12자 이상 — 짧으면 기동 거절)를 바꾸면 다음 기동 때 반영합니다. 같은 아이디의 일반 계정이 이미 있으면 관리자로 올리지 않습니다(오류 로그, 관리 기능 꺼짐). 설정을 지우거나 아이디를 바꾸면 예전 관리자 계정은 로그인할 수 없습니다 |
| `GATE_CODES` | | – (끔) | 접근 코드 잠금. `이름:scrypt해시,이름:scrypt해시` — 있으면 코드를 맞힌 브라우저만 화면·API 에 닿습니다(헬스 체크 제외). 해시 만드는 법은 §3.2 (ADR-0013) |
| `GATE_SECRET` | 잠금 시 ✔ | – | 잠금 해제 쿠키 서명 키(32바이트 이상). 바꾸면 모두 다시 코드를 입력합니다 |
| `GATE_HOURS` / `GATE_COOKIE_TTL` | | – / `12h` | 잠금화면에 적을 운영 시간(비우면 줄을 감춤) / 잠금을 푼 뒤 다시 묻기까지의 시간 |
| `TELEGRAM_BOT_TOKEN` | | – (끔) | 접근 코드 잠금 알림을 보낼 텔레그램 봇 토큰(@BotFather). `TELEGRAM_CHAT_ID` 와 둘 다 있고 잠금이 켜져 있을 때만 잠금 해제(같은 IP 는 1시간에 한 번, 전체 10분에 10건까지)·IP 잠금·전체 잠금을 알립니다. 하나만 있으면 끄고 기동 로그에 경고 (ADR-0017) |
| `TELEGRAM_CHAT_ID` | | – (끔) | 알림을 받을 대화 ID — 봇에게 메시지를 한 번 보낸 뒤 `getUpdates` 응답의 `chat.id` |
| `SWAGGER_ENABLED` | | `false` (`prod`) | 운영에서 API 문서 노출 여부 |
| `SQL_LOG_LEVEL` | | `warn` | 실행한 SQL 로그(`debug` 면 모두 출력). 문제를 볼 때만 잠깐 |
| `PORT` | | `8080` | 앱이 듣는 포트(App Service 등). Docker Compose 에서는 컨테이너 안 8080 고정이고 밖의 포트는 `APP_PORT` |

## 3. Azure (App Service + Database for MySQL + Blob Storage)

v1 과 같은 Azure 구성을 그대로 쓸 수 있습니다.

### 3.1 리소스

1. **Azure Database for MySQL – Flexible Server** (MySQL 8.0 이상): DB `smartcollab` 과 전용 사용자를 만듭니다. 스키마는 앱이 시작할 때 Flyway 가 만듭니다.
2. **Storage Account**: 연결 문자열을 준비합니다. 컨테이너는 앱이 없으면 만듭니다.
3. **App Service (Linux)**: 둘 중 하나
   - **컨테이너**: 저장소의 `Dockerfile` 로 만든 이미지를 레지스트리(ACR·GHCR)에 올려 사용
   - **Java 21 런타임**: `cd backend && ./gradlew bootJar -PbundleFrontend` 로 만든 `build/libs/smartcollab.jar` 배포

### 3.2 App Service 설정

```bash
# WebSocket 사용 (채팅·실시간 반영에 필수)
az webapp config set -g <리소스그룹> -n <앱이름> --web-sockets-enabled true

# 환경 변수
az webapp config appsettings set -g <리소스그룹> -n <앱이름> --settings \
  SPRING_PROFILES_ACTIVE=prod \
  DB_URL="jdbc:mysql://<서버>.mysql.database.azure.com:3306/smartcollab?sslMode=REQUIRED&serverTimezone=UTC" \
  DB_USERNAME=<사용자> DB_PASSWORD=<비밀번호> \
  JWT_SECRET=<32바이트 이상 무작위> \
  AZURE_STORAGE_CONNECTION_STRING="<연결 문자열>"

# Java 런타임으로 jar 배포하는 경우 (컨테이너는 Dockerfile 에 이미 포함)
az webapp config appsettings set -g <리소스그룹> -n <앱이름> --settings \
  JAVA_OPTS="-Djdk.httpclient.allowRestrictedHeaders=content-length"   # Azure SDK 요청마다 남는 경고 방지
az webapp deploy -g <리소스그룹> -n <앱이름> --src-path backend/build/libs/smartcollab.jar --type jar
```

- 공개 데모라면 접근 코드 잠금을 켜세요(ADR-0013). 코드 원문은 서버에 두지 않고 scrypt 해시만 넣습니다. 포트폴리오의 다른 서비스 잠금(gate)과
  형식이 같아 그쪽 `gate.py hash` 출력도 그대로 쓸 수 있습니다. 코드를 여러 개(예: 회사별) 두면 로그(`security-event gate-unlocked code=이름`)로
  누가 열었는지 알 수 있고, 목록에서 지우면 그 코드로 받은 쿠키도 바로 무효가 됩니다.
  `TELEGRAM_BOT_TOKEN`·`TELEGRAM_CHAT_ID` 를 함께 넣으면 잠금 해제(같은 IP 는 1시간에 한 번, 전체 10분에 10건까지)·IP 잠금·전체 잠금을 텔레그램으로 받습니다 — 서비스·코드 이름·가린 IP·브라우저/OS 만 보냅니다(ADR-0017).

  ```bash
  # 코드를 입력(화면에 보이지 않음)하면 해시를 출력 → GATE_CODES=이름:<해시>
  read -rs code && printf '%s' "$code" | python3 -I -c 'import sys,os,hashlib,base64
  e=lambda b: base64.urlsafe_b64encode(b).rstrip(b"=").decode(); c=sys.stdin.read().encode(); s=os.urandom(16)
  print("scrypt.16384.8.1."+e(s)+"."+e(hashlib.scrypt(c,salt=s,n=16384,r=8,p=1,dklen=32)))'
  az webapp config appsettings set -g <리소스그룹> -n <앱이름> -o none --settings \
    GATE_CODES="recruit:<해시>" GATE_SECRET="$(openssl rand -base64 48)" GATE_HOURS="상시"
  ```
- 관리 전용 계정을 쓰려면 `ADMIN_USERNAME`·`ADMIN_PASSWORD` 를 넣으세요(ADR-0015). 비밀번호는 저장소·문서에 적지 말고 무작위로 만들어
  권한 600 의 파일에만 둡니다. 아래는 화면에 비밀번호를 출력하지 않습니다.

  ```bash
  pw="$(openssl rand -base64 24)" && printf 'ADMIN_PASSWORD=%s\n' "$pw" >> ~/<비밀값 파일> && chmod 600 ~/<비밀값 파일>
  az webapp config appsettings set -g <리소스그룹> -n <앱이름> -o none --settings ADMIN_USERNAME=admin ADMIN_PASSWORD="$pw"
  ```
- 헬스 체크 경로: `/actuator/health/readiness` — DB 에 닿지 않으면 503 이 되어 트래픽을 받지 않습니다(liveness 는 그대로 200 이라 재시작되지 않음) [IMP-04]
- 메모리: App Service 플랜의 메모리가 곧 한도입니다. 컨테이너 이미지는 그 75% 를 힙으로 씁니다(`JAVA_TOOL_OPTIONS` 의 `MaxRAMPercentage`). jar 배포라면 `JAVA_OPTS` 에 `-XX:MaxRAMPercentage=75` 를 더하세요.
- HTTPS 전용으로 설정하세요(`COOKIE_SECURE=true` 가 `prod` 프로필 기본값이므로 HTTP 로는 로그인 쿠키가 전송되지 않습니다).
- 배포 후 클라이언트 IP 판별을 확인하세요: 한 네트워크에서 로그인을 11번 틀리면 429, 그 사이 다른 네트워크(휴대폰 데이터 등)에서는 정상 로그인되어야 합니다. 모두 함께 막히면 프록시 주소가 `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` 에 포함되지 않은 것입니다.
- 기동 대기 한도를 늘리세요: `az webapp config appsettings set ... --settings WEBSITES_CONTAINER_START_TIME_LIMIT=600`. 기본값 230초 안에
  준비되지 않으면 App Service 가 기동을 실패로 보고 다시 띄웁니다. B1(1코어)에서 기동이 129~210초(프로세스 기준 최대 236초) 걸렸고,
  기동 때 DB 연결 재시도(`DB_CONNECT_RETRIES`)가 최대 약 1분을 더할 수 있습니다 [DEP-02]
- 1코어 플랜(B1 등)에서 가상 스레드를 켜지 마세요(`SPRING_THREADS_VIRTUAL_ENABLED` 를 두지 않으면 꺼짐). JVM 이 CPU 를 1개로 보면 가상 스레드의
  운반 스레드도 1개라, Java 21 에서는 큰 파일 한 건을 내려받는 동안 서버 전체가 멈췄습니다([ADR-0014](adr/0014-platform-threads-on-java21.md), C-1).
  기동 로그의 `JVM runtime: availableProcessors=… maxHeap=… virtualThreads=…` 한 줄로 확인할 수 있습니다.
- 파일 저장소(Blob) 호출은 시도당 30초·3회까지 기다리고, 그래도 응답이 없으면 503(`Retry-After: 5`)으로 답합니다(최악 약 95초로 App Service 앞단
  230초 제한 안). 업로드는 4MB 블록 단위라 업로드 한 건이 쓰는 메모리가 파일 크기와 상관없습니다 [M-3·H-1].
- 인스턴스는 1개로 운영하세요. 실시간 브로커·요청 제한·탈퇴 계정 거절 목록이 인메모리입니다([ARCHITECTURE.md §7](ARCHITECTURE.md#7-한계와-확장-방안)).

### 3.3 v2 를 이미 배포했다면

새 버전이 기동하면 Flyway 가 추가된 마이그레이션(V2 알림 인덱스, V3 폴더 휴지통 열·인덱스, V4 저장 사용량 집계 열)을 자동 적용합니다. 열·인덱스 추가뿐이라 기존 데이터는 바뀌지 않습니다. V4 는 `users.stored_bytes`·`teams.stored_bytes` 를 더하고 지금까지의 버전 합계로 채웁니다(한 번 전체 합산).

**폴더 휴지통(V3) 이전 버전으로 되돌릴 때**는 먼저 휴지통의 폴더를 모두 복원하거나 영구 삭제하세요. 이전 버전은 폴더의 휴지통 표시를 몰라 휴지통의 폴더가 드라이브에 다시 보이고, 맨 위 폴더가 자기 자신을 가리키는 외래 키 때문에 그 폴더를 지울 수도 없습니다(MySQL 8.4 에서 오류 1451 확인). 다른 사용자의 휴지통은 화면에서 복원할 수 없으므로, 운영자는 다음 SQL 로 한 번에 복원합니다.

```sql
UPDATE folders SET trash_root_id = NULL, deleted_at = NULL, deleted_by = NULL WHERE trash_root_id IS NOT NULL;
```

**저장 사용량 집계(V4) 이전 버전으로 되돌렸다가 다시 올릴 때**: 이전 버전은 V4 열이 있는 DB 에서도 기동하지만(QA 스택에서 확인) 집계를 고치지 않습니다. 다시 올리면 그 사이의 업로드·삭제만큼 집계가 어긋나 있어(QA 스택: 이전 버전으로 1분 쓰기 부하 뒤 사용자 50명), 매일 04:30 정리 작업 전까지는 한도 판단이 틀릴 수 있습니다. 다시 올린 직후 V4 의 채우기 UPDATE 두 문장(`V4__storage_usage_counters.sql`)을 한 번 실행하거나, `USAGE_RECONCILE_CRON` 을 잠시 짧게 두세요.

추가된 열·인덱스는 남겨 둬도 이전 버전이 기동합니다. Flyway 는 로컬에 없는 더 높은 버전(V3)을 기본 설정(`ignoreMigrationPatterns=*:future`, 12.4.0 에서 확인)으로 건너뛰고, Hibernate `validate` 는 엔티티에 없는 열을 검사하지 않습니다.

### 3.4 v1 데이터베이스에서 옮길 때

v2 스키마는 v1 과 다릅니다(시각을 UTC 로 저장, 파일 원본 키를 버전 테이블로 이동, 공유 링크 토큰·비밀번호 컬럼 변경 등). **새 데이터베이스로 시작하는 것을 권장**합니다. v1 데이터를 유지해야 한다면 v1 스키마를 읽어 v2 테이블로 옮기는 일회성 이전 스크립트가 필요합니다.

## 4. 백업과 복원

출시 기준 QA 에서 로컬(Docker Compose, 저장소 `local`) 절차를 복원까지 검증했습니다([qa/scripts/backup-restore.sh](../qa/scripts/backup-restore.sh)).
파일 6개의 SHA-256·버전 수·휴지통·팀·사용량이 원본과 같았고, DB·저장소 정합성 검사도 모두 0 이었습니다([QA_2026-10-03 §9-4](QA_2026-10-03.md#9-4-영역별-결과)).

DB 와 저장소 파일은 따로 저장되므로 **둘을 같은 시점으로 맞춰야** 합니다. 앱을 멈추지 않고 DB 덤프와 파일 복사를 차례로 하면 그 사이의 변경이 어긋납니다.
- 업로드는 파일을 먼저 쓰고 DB 를 나중에 씁니다. 그래서 파일을 먼저 복사하면, 그 뒤 업로드된 파일의 행은 덤프에 있는데 파일은 없습니다.
- 삭제는 DB 를 먼저 지우고 파일을 나중에 지웁니다. 그래서 덤프 뒤 파일을 복사하면, 덤프에 남은 행의 파일이 이미 지워졌을 수 있습니다.

```bash
docker compose stop app                                                   # 쓰기 중지(검증 때 3초)
docker compose exec -T -e MYSQL_PWD="$DB_ROOT_PASSWORD" db mysqldump -uroot --single-transaction --routines --triggers --set-gtid-purged=OFF smartcollab > db.sql
docker compose run --rm --no-deps -T --entrypoint tar app czf - -C /data . > files.tgz
docker compose start app
```

복원은 빈 스택에서 DB 만 먼저 띄워 `db.sql` 을 넣고, 저장소에 `files.tgz` 를 푼 뒤 앱을 띄웁니다(스크립트의 3단계).
Azure 에서는 Database for MySQL 의 자동 백업(시점 복원)과 Blob Storage 의 소프트 삭제로 같은 시점을 맞춥니다. 6차 QA 에서 운영 리소스의 설정을
읽기 전용으로 확인했습니다(2026-10-13): MySQL 백업 보존 7일·지역 중복 꺼짐, Blob 소프트 삭제 7일·컨테이너 소프트 삭제 7일, **버전 관리·변경 피드·시점 복원은 꺼짐**.
이전 문서는 "버전 관리를 함께 켠다"고 적었지만 실제로는 꺼져 있었습니다 [QA6-12]. 이 앱은 저장 키(UUID)를 한 번 쓰고 덮어쓰지 않으므로 버전 관리 없이도
되돌릴 수 있습니다 — 단, 아래를 보존 기간(7일) 안에 해야 합니다.

1. DB 를 복원할 시각 T 로 시점 복원합니다(새 서버로 복원된 뒤 `DB_URL` 을 그쪽으로 바꿈).
2. T 이후에 지워진 blob 을 되살립니다: `az storage blob list --include d -c <컨테이너> --query "[?deleted]"` 로 찾아 `az storage blob undelete`.
   (T 에는 있던 파일 행이 가리키는 blob 이므로 되살리지 않으면 내려받기가 404 입니다)
3. T 이후에 올라온 blob 은 복원된 DB 가 가리키지 않으므로 고아 정리(24시간 유예)가 지웁니다.

이 Azure 경로(시점 복원·되살리기)는 실제 리소스가 필요해 복원까지 해 보지는 않았습니다. 로컬 절차는 6차 QA 에서 다시 복원까지 확인했습니다
([QA_2026-10-13](QA_2026-10-13.md)).

## 5. CI

`.github/workflows/ci.yml` 이 푸시·PR 마다 실행합니다.

| 작업 | 내용 |
|---|---|
| backend | 단위·통합 테스트(MySQL·Azurite Testcontainers), CPU 1개 JVM 시험(`singleCarrierTest`, C-1), 커버리지·측정 리포트 업로드 |
| frontend | 타입 검사 · ESLint · Vitest · 빌드 |
| e2e | Docker 이미지로 전체 스택 실행 → Playwright 사용자 시나리오 |
| secrets | gitleaks 로 전체 커밋 이력 비밀값 검사 |

## 6. 비용과 저장 한도

저장 한도를 정할 때 쓴 값입니다(2026-10-15 측정, 원자료 [measurements/azure-cost.json](measurements/azure-cost.json)). 단가는 Azure 소매가 API 의
Korea Central 종량제 가격(원), 비용은 Cost Management 의 이번 달 실제 비용입니다.

| 항목 | 단가 | 월 비용 |
|---|---|---|
| App Service B1(Linux) | 시간당 24.57원 | 약 17,900원 — 지금 과금되는 유일한 항목 |
| Database for MySQL B1ms + 저장 32GB | 시간당 35.48원 + GB 당 178.78원 | 무료 계정 12개월 동안 0원(750시간·32GB 무료), 그 뒤 약 31,600원 |
| Blob Storage(Hot·LRS) 저장 | GB 당 월 27.30원 | 12개월 동안 5GB 무료. 지금 사용량 229MB |
| Blob 쓰기 / 읽기 요청 | 1만 회당 68.24원 / 5.46원 | 12개월 동안 월 쓰기 1만·읽기 2만 회 무료. 업로드는 4MB 블록마다 쓰기 1회라 2GB 를 채워도 약 520회(4원 남짓) |
| 인터넷 송신(내려받기·미리보기) | 월 100GB 무료, 그 뒤 GB 당 약 164원 | 저장보다 GB 당 6배 비쌈 |

월 예산 100,000원(예산 알림 기준)에서 고정비를 뺀 나머지를 모두 저장에 쓴다고 보면, 계정마다 2GB 를 꽉 채워도 다음 수까지 예산 안입니다
(계정 수를 정하기 전의 계산 — 지금은 계정을 20개로 막음, 아래).

| 경우 | 무료 12개월 동안 (여유 약 82,000원) | 그 뒤 (여유 약 50,400원) |
|---|---|---|
| 저장만 | 약 3,000GB → 2GB 계정 약 1,500개 | 약 1,850GB → 약 920개 |
| 저장 2GB + 한 달에 2GB 내려받기 | 약 250개 | 약 170개 |

- 한도는 최댓값이고 실제 비용은 쓴 만큼입니다. 저장량은 Azure Monitor 의 `UsedCapacity`, 비용은 Cost Management 에서 봅니다.
- **지금 설정의 최대치**: 계정 20개(체험 3 포함), 한 계정의 팀 6개(팀장·멤버 포함). 일반 계정 17개가 모두 팀 6개를 이끌면 팀 102개 × 5GB,
  여기에 개인 드라이브 20개 × 2GB 와 체험 팀 2개 × 2GB 를 더해 **최대 554GB — 저장비 월 약 15,000원**으로 무료 기간 뒤에도 예산 안입니다.
  내려받기(송신)는 한도로 막지 않으므로, 554GB 를 한 달에 모두 내려받는 극단적인 경우에만 무료 기간 뒤 예산을 넘습니다(약 74,000원).
  가입은 IP 당 시간당 5회, 공개 데모는 접근 코드로 막습니다(ADR-0013).
- 체험 계정(3개)과 체험 팀은 매일 초기화되므로 저장량이 쌓이지 않습니다.
