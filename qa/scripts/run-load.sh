#!/bin/bash
# 부하 측정 실행 — QA 스택(sc-qa)에서만. 시드(perf-seed.mjs) 뒤에 실행합니다.
# 느린 쿼리 로그를 비운 뒤 k6 읽기(10·50·100명)·쓰기(10·50명)·로그인(20명)을 차례로 돌리고, 컨테이너 자원을 5초마다 기록합니다.
# 인자 docker: k6 를 QA 스택과 같은 Docker 네트워크 안에서 실행(macOS 호스트 → Docker VM 포트 포워딩을 빼고 측정)
set -uo pipefail
cd "$(dirname "$0")/../.."
MODE=${1:-host}
R=qa/results/$MODE
mkdir -p $R
k6run() {
  if [ "$MODE" = docker ]; then
    docker run --rm --network sc-qa_default -v "$PWD/qa:/work/qa" -w /work grafana/k6:1.3.0 run -q -e BASE=http://app:8080 -e OUT=$R "$@" qa/k6/load.js
  else
    k6 run -q -e OUT=$R "$@" qa/k6/load.js
  fi
}
PW=$(grep -E '^DB_ROOT_PASSWORD=' .env | cut -d= -f2-)
docker exec sc-qa-db-1 sh -c 'cp /var/lib/mysql/slow.log /var/lib/mysql/slow-before-load.log; : > /var/lib/mysql/slow.log'
docker exec -e MYSQL_PWD="$PW" sc-qa-db-1 mysql -uroot -e "FLUSH SLOW LOGS" 2>/dev/null
# 앱 메모리는 cgroup 의 anon(JVM 힙·네이티브)과 file(파일 캐시)을 나눠 기록합니다 — docker stats 의 사용량에는 파일 캐시도 들어감
( while true; do
    t=$(date +%H:%M:%S)
    docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' sc-qa-app-1 sc-qa-db-1 | sed "s/^/$t,/"
    docker exec sc-qa-app-1 sh -c 'grep -E "^(anon|file) " /sys/fs/cgroup/memory.stat' 2>/dev/null | awk -v t=$t '{printf "%s,app-%s,%.0fMiB\n", t, $1, $2/1048576}'
    sleep 5
  done ) > $R/load-docker-stats.csv &
STATS=$!
trap 'kill $STATS 2>/dev/null' EXIT
for v in 10 50 100; do k6run -e MIX=api -e VUS=$v -e DURATION=60s 2>&1 | grep -v "^\s*$\|level=error" | tail -2; sleep 5; done
for v in 10 50; do k6run -e MIX=read -e VUS=$v -e DURATION=60s 2>&1 | grep -v "^\s*$\|level=error" | tail -2; sleep 5; done
for v in 10 50; do k6run -e MIX=write -e VUS=$v -e DURATION=60s 2>&1 | grep -v "^\s*$\|level=error" | tail -2; sleep 5; done
k6run -e MIX=login -e VUS=20 -e DURATION=30s 2>&1 | grep -v "^\s*$\|level=error" | tail -2
docker cp sc-qa-db-1:/var/lib/mysql/slow.log $R/slow-load.log >/dev/null
python3 qa/scripts/slowlog.py $R/slow-load.log $R/slowlog-load.json
