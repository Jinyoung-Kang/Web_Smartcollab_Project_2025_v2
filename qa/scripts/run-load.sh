#!/bin/bash
# 부하 측정 실행 — QA 스택(sc-qa)에서만. 시드(perf-seed.mjs) 뒤에 실행합니다.
# 느린 쿼리 로그를 비운 뒤 k6 읽기(10·50·100명)·쓰기(10·50명)·로그인(20명)을 차례로 돌리고, 컨테이너 자원을 5초마다 기록합니다.
set -uo pipefail
cd "$(dirname "$0")/../.."
R=qa/results
PW=$(grep -E '^DB_ROOT_PASSWORD=' .env | cut -d= -f2-)
docker exec sc-qa-db-1 sh -c 'cp /var/lib/mysql/slow.log /var/lib/mysql/slow-before-load.log; : > /var/lib/mysql/slow.log'
docker exec -e MYSQL_PWD="$PW" sc-qa-db-1 mysql -uroot -e "FLUSH SLOW LOGS" 2>/dev/null
( while true; do docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' sc-qa-app-1 sc-qa-db-1 | sed "s/^/$(date +%H:%M:%S),/"; sleep 5; done ) > $R/load-docker-stats.csv &
STATS=$!
trap 'kill $STATS 2>/dev/null' EXIT
for v in 10 50 100; do k6 run -q -e MIX=read -e VUS=$v -e DURATION=60s qa/k6/load.js 2>&1 | grep -v "^\s*$" | tail -3; sleep 5; done
for v in 10 50; do k6 run -q -e MIX=write -e VUS=$v -e DURATION=60s qa/k6/load.js 2>&1 | grep -v "^\s*$" | tail -3; sleep 5; done
k6 run -q -e MIX=login -e VUS=20 -e DURATION=30s qa/k6/load.js 2>&1 | grep -v "^\s*$" | tail -3
docker cp sc-qa-db-1:/var/lib/mysql/slow.log $R/slow-load.log >/dev/null
python3 qa/scripts/slowlog.py $R/slow-load.log $R/slowlog-load.json
