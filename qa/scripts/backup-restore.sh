#!/bin/bash
# 백업·복원 후보 절차 검증 (로컬 Docker Compose, 저장소 local) — 별도 임시 스택(sc-bk, 8090/13308)에서만 실행합니다.
#  1) 임시 스택을 띄우고 데이터(사용자·폴더·파일·버전·팀·휴지통)를 만든 뒤 파일마다 SHA-256 을 기록
#  2) 백업: 앱을 멈춰 쓰기를 막고 → mysqldump(--single-transaction) → 파일 저장소(/data) tar → 앱 다시 시작
#  3) 볼륨까지 지운 뒤(down -v) 새 스택에 복원: DB 가져오기 → 파일 풀기 → 앱 시작
#  4) 복원된 스택에서 로그인·목록·다운로드 SHA-256·버전 수·정합성 검사로 원본과 비교
# 사용: qa/scripts/backup-restore.sh   (끝나면 sc-bk 스택과 볼륨을 지움)
set -euo pipefail
cd "$(dirname "$0")/../.."
P=sc-bk
export APP_PORT=8090 DB_PORT=13308
OUT=qa/results/backup-restore
mkdir -p "$OUT"
BK=$(mktemp -d)
PW=$(grep -E '^DB_ROOT_PASSWORD=' .env | cut -d= -f2-)
compose() { docker compose -p $P "$@"; }
log() { echo "[$(date +%H:%M:%S)] $*"; }
trap 'compose down -v >/dev/null 2>&1 || true; rm -rf "$BK"' EXIT

log "1) 임시 스택 시작"
compose up -d --wait >/dev/null 2>&1
QA_BASE_URL=http://localhost:8090 node qa/scripts/backup-seed.mjs create "$OUT/before.json"

log "2) 백업 — 앱 정지 → DB 덤프 → 파일 저장소 tar → 앱 시작"
t0=$(date +%s)
compose stop app >/dev/null 2>&1
docker exec -e MYSQL_PWD="$PW" $P-db-1 mysqldump -uroot --single-transaction --routines --triggers --set-gtid-purged=OFF smartcollab > "$BK/db.sql" 2>/dev/null
compose run --rm --no-deps -T --entrypoint tar app czf - -C /data . > "$BK/files.tgz" 2>/dev/null
compose start app >/dev/null 2>&1
t1=$(date +%s)
log "   덤프 $(du -h "$BK/db.sql" | cut -f1), 파일 $(du -h "$BK/files.tgz" | cut -f1), 쓰기 중지 $((t1 - t0))초"

log "3) 볼륨까지 삭제 후 새 스택에 복원"
compose down -v >/dev/null 2>&1
compose up -d --wait db >/dev/null 2>&1
docker exec -i -e MYSQL_PWD="$PW" $P-db-1 mysql -uroot smartcollab < "$BK/db.sql"
compose run --rm --no-deps -T --entrypoint tar app xzf - -C /data < "$BK/files.tgz" >/dev/null 2>&1
compose up -d --wait app >/dev/null 2>&1

log "4) 복원 확인"
QA_BASE_URL=http://localhost:8090 node qa/scripts/backup-seed.mjs verify "$OUT/before.json" "$OUT/after.json"
qa/scripts/integrity-check.sh $P > "$OUT/integrity-after.json"
cat "$OUT/integrity-after.json"
log "완료"
