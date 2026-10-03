#!/bin/bash
# QA 스택의 DB 와 저장소 파일의 정합성 — 유실(DB 에 있는데 파일 없음)·고아(파일만 있음)·해시 불일치·메타데이터 어긋남
# 사용: qa/scripts/integrity-check.sh [프로젝트=sc-qa]   (비밀번호는 .env 의 DB_ROOT_PASSWORD 를 읽음, 출력하지 않음)
set -euo pipefail
P=${1:-sc-qa}
cd "$(dirname "$0")/../.."
PW=$(grep -E '^DB_ROOT_PASSWORD=' .env | cut -d= -f2-)
sql() { docker exec -e MYSQL_PWD="$PW" "$P-db-1" mysql -uroot -N -B smartcollab -e "$1" 2>/dev/null; }
T=$(mktemp -d)
sql "SELECT stored_path, sha256 FROM file_versions" | sort > "$T/db"
docker exec "$P-app-1" sh -c 'cd /data && find . -type f ! -name ".upload-*" | sed "s|^\./||" | sort' > "$T/fs"
docker exec "$P-app-1" sh -c 'cd /data && find . -type f -name ".upload-*" | wc -l' > "$T/tmpcount"
cut -f1 "$T/db" | sort > "$T/dbkeys"
missing=$(comm -23 "$T/dbkeys" "$T/fs" | wc -l | tr -d ' ')
orphans=$(comm -13 "$T/dbkeys" "$T/fs" | wc -l | tr -d ' ')
# 해시 검사(최대 300개 표본)
bad=0; n=0
while IFS=$'\t' read -r key sha; do
  [ -z "$key" ] && continue
  n=$((n+1)); [ $n -gt 300 ] && break
  actual=$(docker exec "$P-app-1" sh -c "sha256sum '/data/$key' 2>/dev/null | cut -d' ' -f1" || true)
  [ -n "$actual" ] && [ "$actual" != "$sha" ] && bad=$((bad+1))
done < "$T/db"
q() { sql "$1" | head -1; }
# 저장 사용량 집계(V4, IMP-01)와 실제 버전 합계가 다른 저장 공간 수 — V4 전 DB 면 null
scope_sum='SELECT COALESCE(SUM(v.size),0) FROM file_versions v JOIN files f ON f.file_id=v.file_id JOIN folders fo ON fo.folder_id=f.folder_id WHERE'
user_drift=$(q "SELECT COUNT(*) FROM users u WHERE u.stored_bytes <> ($scope_sum fo.team_id IS NULL AND fo.owner_id=u.user_id)")
team_drift=$(q "SELECT COUNT(*) FROM teams t WHERE t.stored_bytes <> ($scope_sum fo.team_id=t.team_id)")
cat <<JSON
{
  "versions": $(wc -l < "$T/dbkeys" | tr -d ' '),
  "blobs": $(wc -l < "$T/fs" | tr -d ' '),
  "missingBlobs": $missing,
  "orphanBlobs": $orphans,
  "tempUploads": $(tr -d ' ' < "$T/tmpcount"),
  "hashMismatch(sample<=300)": $bad,
  "filesWithoutActiveVersion": $(q "SELECT COUNT(*) FROM files WHERE active_version_id IS NULL"),
  "activeVersionOfOtherFile": $(q "SELECT COUNT(*) FROM files f JOIN file_versions v ON v.version_id=f.active_version_id WHERE v.file_id<>f.file_id"),
  "fileSizeMismatch": $(q "SELECT COUNT(*) FROM files f JOIN file_versions v ON v.version_id=f.active_version_id WHERE v.size<>f.size"),
  "childScopeMismatch": $(q "SELECT COUNT(*) FROM folders c JOIN folders p ON p.folder_id=c.parent_folder_id WHERE NOT (c.team_id <=> p.team_id) OR (c.team_id IS NULL AND c.owner_id<>p.owner_id)"),
  "trashRootNotTrashed": $(q "SELECT COUNT(*) FROM folders c JOIN folders r ON r.folder_id=c.trash_root_id WHERE r.trash_root_id IS NULL OR r.trash_root_id<>r.folder_id"),
  "childOfTrashedNotTrashed": $(q "SELECT COUNT(*) FROM folders c JOIN folders p ON p.folder_id=c.parent_folder_id WHERE p.trash_root_id IS NOT NULL AND c.trash_root_id IS NULL"),
  "teamsWithoutOneLeader": $(q "SELECT COUNT(*) FROM teams t WHERE (SELECT COUNT(*) FROM team_members m WHERE m.team_id=t.team_id AND m.user_id=t.owner_id) <> 1"),
  "duplicateMembers": $(q "SELECT COUNT(*) FROM (SELECT team_id,user_id FROM team_members GROUP BY team_id,user_id HAVING COUNT(*)>1) x"),
  "usageCounterDrift": { "users": ${user_drift:-null}, "teams": ${team_drift:-null} }
}
JSON
rm -rf "$T"
