#!/bin/bash
# Lighthouse(npm, 설치된 Chrome 사용)로 화면 성능·접근성·모범 사례를 측정합니다 — QA 스택(8080)에서만.
# 로그인이 필요한 화면은 QA 사용자의 JWT 를 Authorization: Bearer 헤더로 넘깁니다(Chrome 은 덧붙인 Cookie 헤더를 쓰지 않아
# 로그인 화면으로 돌아가 버림 — 첫 측정에서 확인). 화면마다 데스크톱·모바일 각 3회, 점수 중앙값을 남깁니다.
# 사용: qa/scripts/lighthouse.sh   → qa/results/lighthouse-summary.json (원본 보고서는 저장하지 않음)
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT=qa/results
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
export CHROME_PATH="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
read -r COOKIE TEXT SHARE <<< "$(node --input-type=module -e "
import { Client } from './qa/scripts/lib.mjs'
const c = await new Client().signup()
const t = (await c.upload(c.me.rootFolderId, '회의록.txt', '첫 문장입니다. 두 번째 문장입니다.', 'text/plain')).data
for (let i = 0; i < 20; i++) await c.upload(c.me.rootFolderId, '파일-' + i + '.txt', 'x' + i, 'text/plain')
const l = (await c.post('/api/files/' + t.id + '/share-links', {})).data
console.log(c.cookies.get('SC_AUTH'), t.id, l.token)
")"
PAGES=("login|/login|anon" "drive|/drive|auth" "editor|/files/$TEXT/edit|auth" "share|/share/$SHARE|anon")
for entry in "${PAGES[@]}"; do
  IFS='|' read -r name path auth <<< "$entry"
  for preset in desktop mobile; do
    for run in 1 2 3; do
      args=(--quiet --output=json --output-path="$TMP/$name-$preset-$run.json" --only-categories=performance,accessibility,best-practices
            --chrome-flags="--headless=new --no-sandbox")
      [ "$preset" = desktop ] && args+=(--preset=desktop)
      [ "$auth" = auth ] && args+=(--extra-headers="{\"Authorization\":\"Bearer $COOKIE\"}")
      npx -y lighthouse@13.5.0 "http://localhost:8080$path" "${args[@]}" >/dev/null 2>&1 || echo "실패: $name $preset $run"
    done
  done
done
python3 - "$TMP" "$OUT/lighthouse-summary.json" <<'PY'
import glob, json, statistics, sys, os
tmp, out = sys.argv[1], sys.argv[2]
rows = {}
for f in glob.glob(f'{tmp}/*.json'):
    name, preset, _ = os.path.basename(f)[:-5].rsplit('-', 2)
    r = json.load(open(f))
    a = r['audits']
    rows.setdefault((name, preset), []).append({
        'performance': round(r['categories']['performance']['score'] * 100),
        'accessibility': round(r['categories']['accessibility']['score'] * 100),
        'bestPractices': round(r['categories']['best-practices']['score'] * 100),
        'fcpMs': a['first-contentful-paint']['numericValue'], 'lcpMs': a['largest-contentful-paint']['numericValue'],
        'tbtMs': a['total-blocking-time']['numericValue'], 'cls': a['cumulative-layout-shift']['numericValue'],
        'transferKb': a['total-byte-weight']['numericValue'] / 1024,
        'authMe401': any('/api/auth/me' in (i.get('sourceLocation') or {}).get('url', '') for i in a['errors-in-console'].get('details', {}).get('items', [])),
        'failedAudits': sorted(k for k, v in a.items() if v.get('score') is not None and v['score'] < 0.9 and v.get('scoreDisplayMode') in ('binary', 'numeric'))[:12],
    })
summary = []
for (name, preset), runs in sorted(rows.items()):
    med = {k: statistics.median(r[k] for r in runs) for k in runs[0] if k != 'failedAudits'}
    summary.append({'page': name, 'preset': preset, 'runs': len(runs), **{k: round(v, 1) for k, v in med.items()}, 'failedAudits(1회차)': runs[0]['failedAudits']})
json.dump({'tool': 'lighthouse 13.5.0', 'summary': summary}, open(out, 'w'), ensure_ascii=False, indent=2)
for s in summary:
    print(f"{s['page']:7} {s['preset']:7} perf={s['performance']:5} a11y={s['accessibility']:5} bp={s['bestPractices']:5} FCP={s['fcpMs']:.0f} LCP={s['lcpMs']:.0f} TBT={s['tbtMs']:.0f} CLS={s['cls']} {s['transferKb']:.0f}KB")
PY
