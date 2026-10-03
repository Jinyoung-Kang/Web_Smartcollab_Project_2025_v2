"""백엔드 런타임 의존성 좌표(qa/results/runtime-deps.txt)를 OSV querybatch API 로 조회합니다.
실행: python3 qa/scripts/osv-check.py  (공개 무료 API, 한 번 호출)"""
import json, pathlib, urllib.request

deps = [l.strip() for l in pathlib.Path('qa/results/runtime-deps.txt').read_text().splitlines() if l.strip()]
queries = []
for d in deps:
    g, a, v = d.split(':')[:3]
    queries.append({'package': {'ecosystem': 'Maven', 'name': f'{g}:{a}'}, 'version': v})
req = urllib.request.Request('https://api.osv.dev/v1/querybatch', data=json.dumps({'queries': queries}).encode(),
                             headers={'content-type': 'application/json'})
res = json.load(urllib.request.urlopen(req, timeout=60))
hits = [(deps[i], [v['id'] for v in r.get('vulns', [])]) for i, r in enumerate(res['results']) if r.get('vulns')]
out = {'checked': len(deps), 'vulnerable': len(hits), 'hits': hits}
pathlib.Path('qa/results/osv-backend.json').write_text(json.dumps(out, indent=2))
print(json.dumps(out, indent=2))
