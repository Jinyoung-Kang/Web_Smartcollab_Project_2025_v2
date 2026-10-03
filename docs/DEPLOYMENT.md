# 배포 가이드

## 1. 로컬 (Docker Compose)

```bash
cp .env.example .env         # JWT_SECRET 등 값 입력 (openssl rand -base64 48)
docker compose up -d --build --wait
open http://localhost:8080
```

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
| `DB_CONNECTION_TIMEOUT_MS` | | `5000` | DB 커넥션을 기다리는 최대 시간(ms). DB 장애 중 요청을 오래 붙잡지 않도록 짧게 둡니다 |
| `STORAGE_TYPE` | | `local` (`prod` 는 `azure`) | `local` \| `azure` |
| `STORAGE_LOCAL_ROOT` | | `~/smartcollab-data` | 로컬 저장소 경로 |
| `AZURE_STORAGE_CONNECTION_STRING` | azure 시 ✔ | – | Blob Storage 연결 문자열 |
| `AZURE_STORAGE_CONTAINER` | | `smartcollab-files` | 컨테이너 이름 (없으면 생성) |
| `COOKIE_SECURE` | | `false` (`prod` 는 `true`) | HTTPS 에서만 쿠키 전송 |
| `CORS_ALLOWED_ORIGINS` | | 개발 `http://localhost:5173,…` / `prod` 는 비어 있음 | 다른 출처에서 API 를 부를 때만 |
| `UPLOAD_MAX_FILE_SIZE` | | `200MB` | 업로드 한도 |
| `MAX_REQUEST_BODY_SIZE` | | `6MB` | 업로드를 뺀 요청 본문(JSON) 한도. `TEXT_EDIT_MAX_BYTES` 를 올리면 그 2배 이상으로 함께 올리세요 |
| `TRASH_RETENTION_DAYS` | | `30` | 휴지통 보관 기간 |
| `QUOTA_PERSONAL` / `QUOTA_TEAM` | | `1GB` / `5GB` | 저장 공간 한도 (옛 버전·휴지통 포함) |
| `MAX_TEAMS_PER_USER` | | `10` | 한 사람이 팀장인 팀 수 (팀마다 저장 한도를 받으므로) |
| `MAX_FOLDER_DEPTH` / `MAX_COPY_FOLDERS` | | `50` / `1000` | 폴더 깊이(최상위 아래 단계) / 한 번에 복사하는 폴더 수 |
| `SIGNUP_RATE_PER_HOUR` | | `5` | IP 당 시간당 가입 횟수 |
| `DEMO_QUOTA` | | `50MB` | 체험 계정·체험 팀의 저장 한도 |
| `DEMO_RESET_CRON` / `DEMO_RESET_ZONE` | | `0 0 5 * * *` / `Asia/Seoul` | 체험 데이터 초기화 시각 |
| `TRASH_PURGE_CRON` / `TRASH_PURGE_ZONE` | | `0 0 4 * * *` / `Asia/Seoul` | 휴지통 자동 비우기 시각과 그 기준 시간대 |
| `LOGIN_RATE_PER_MINUTE` / `LOGIN_ACCOUNT_RATE` | | `10` / `20` | 로그인 시도 한도 (IP 당 분당 / 계정당 10분) |
| `SHARE_PASSWORD_RATE` / `SHARE_PASSWORD_LINK_RATE` | | `10` / `50` | 공유 비밀번호 시도 한도 (링크+IP 당 / 링크당, 10분) |
| `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` | | Tomcat 기본값(사설·루프백 대역) | X-Forwarded-For 를 믿을 프록시 주소(정규식). 프록시 없이 직접 노출할 때는 좁히세요 |
| `DEEPL_API_KEY` | | – | 번역 기능 (없으면 번역 버튼 비활성) |
| `TRANSLATION_CHARS_PER_USER_PER_DAY` | | `100000` | 사용자별 24시간 번역 글자 수. DeepL 요금제의 월 한도에 맞춰 조정하세요 |
| `DEMO_ENABLED` / `DEMO_PASSWORD` | | `false` | 체험 계정·데이터 생성. 비밀번호가 공개 설정 API 로 안내되므로 체험 전용 배포에서만 켜세요 |
| `SWAGGER_ENABLED` | | `false` (`prod`) | 운영에서 API 문서 노출 여부 |

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

- 헬스 체크 경로: `/actuator/health/readiness`
- HTTPS 전용으로 설정하세요(`COOKIE_SECURE=true` 가 `prod` 프로필 기본값이므로 HTTP 로는 로그인 쿠키가 전송되지 않습니다).
- 배포 후 클라이언트 IP 판별을 확인하세요: 한 네트워크에서 로그인을 11번 틀리면 429, 그 사이 다른 네트워크(휴대폰 데이터 등)에서는 정상 로그인되어야 합니다. 모두 함께 막히면 프록시 주소가 `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` 에 포함되지 않은 것입니다.
- 인스턴스는 1개로 운영하세요. 실시간 브로커·요청 제한이 인메모리입니다([ARCHITECTURE.md §7](ARCHITECTURE.md#7-한계와-확장-방안)).

### 3.3 v2 를 이미 배포했다면

새 버전이 기동하면 Flyway 가 추가된 마이그레이션(V2 알림 인덱스, V3 폴더 휴지통 열·인덱스)을 자동 적용합니다. 열·인덱스 추가뿐이라 기존 데이터는 바뀌지 않습니다.

**폴더 휴지통(V3) 이전 버전으로 되돌릴 때**는 먼저 휴지통의 폴더를 모두 복원하거나 영구 삭제하세요. 이전 버전은 폴더의 휴지통 표시를 몰라 휴지통의 폴더가 드라이브에 다시 보이고, 맨 위 폴더가 자기 자신을 가리키는 외래 키 때문에 그 폴더를 지울 수도 없습니다(MySQL 8.4 에서 오류 1451 확인). 다른 사용자의 휴지통은 화면에서 복원할 수 없으므로, 운영자는 다음 SQL 로 한 번에 복원합니다.

```sql
UPDATE folders SET trash_root_id = NULL, deleted_at = NULL, deleted_by = NULL WHERE trash_root_id IS NOT NULL;
```

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
Azure 에서는 Database for MySQL 의 자동 백업(시점 복원)과 Blob Storage 의 소프트 삭제·버전 관리를 함께 켜고, 같은 시점으로 복원합니다. 이 경로는 실제 리소스가 필요해 검증하지 않았습니다.

## 5. CI

`.github/workflows/ci.yml` 이 푸시·PR 마다 실행합니다.

| 작업 | 내용 |
|---|---|
| backend | 단위·통합 테스트(MySQL·Azurite Testcontainers), 커버리지·측정 리포트 업로드 |
| frontend | 타입 검사 · ESLint · Vitest · 빌드 |
| e2e | Docker 이미지로 전체 스택 실행 → Playwright 사용자 시나리오 |
| secrets | gitleaks 로 전체 커밋 이력 비밀값 검사 |
