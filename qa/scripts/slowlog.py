"""QA 스택 MySQL 느린 쿼리 로그 요약 — 쿼리 모양(숫자·문자열을 ?로)별 횟수·총 시간·최대·평균 검사 행 수.
사용: python3 qa/scripts/slowlog.py <slow.log 경로> [결과 json 경로]
로그는 long_query_time=0.05 와 log_queries_not_using_indexes=ON 으로 남깁니다(qa/compose.qa.yml)."""
import json
import re
import sys
from collections import defaultdict

path = sys.argv[1]
out = sys.argv[2] if len(sys.argv) > 2 else None
text = open(path, encoding='utf-8', errors='replace').read()
entries = re.split(r'^# Time: .*$', text, flags=re.M)[1:]
agg = defaultdict(lambda: {'count': 0, 'total': 0.0, 'max': 0.0, 'rows_examined': 0, 'rows_sent': 0, 'example': ''})
for e in entries:
    m = re.search(r'# Query_time: ([\d.]+)\s+Lock_time: ([\d.]+)\s+Rows_sent: (\d+)\s+Rows_examined: (\d+)', e)
    if not m:
        continue
    qt, _, sent, examined = float(m.group(1)), float(m.group(2)), int(m.group(3)), int(m.group(4))
    sql = '\n'.join(line for line in e.splitlines() if line and not line.startswith('#') and not line.startswith('SET timestamp') and not line.startswith('use '))
    sql = sql.strip().rstrip(';')
    if not sql or sql.startswith(('administrator command', '/*')):
        continue
    shape = re.sub(r"'(?:[^'\\]|\\.)*'", '?', sql)
    shape = re.sub(r'\b\d+\b', '?', shape)
    shape = re.sub(r'\(\s*\?(?:\s*,\s*\?)+\s*\)', '(?, …)', shape)
    shape = re.sub(r'\s+', ' ', shape)[:400]
    a = agg[shape]
    a['count'] += 1
    a['total'] += qt
    a['max'] = max(a['max'], qt)
    a['rows_examined'] += examined
    a['rows_sent'] += sent
    if not a['example']:
        a['example'] = sql[:600]
rows = sorted(({'shape': k, **v, 'avg_examined': v['rows_examined'] // max(v['count'], 1)} for k, v in agg.items()), key=lambda r: -r['total'])
slow = [r for r in rows if r['max'] >= 0.05]
summary = {'entries': len(entries), 'shapes': len(rows), 'slowShapes(max>=50ms)': len(slow), 'topByTotal': rows[:15], 'slow': slow[:15]}
if out:
    open(out, 'w', encoding='utf-8').write(json.dumps(summary, ensure_ascii=False, indent=2))
print(f"entries={len(entries)} shapes={len(rows)} slowShapes={len(slow)}")
for r in rows[:15]:
    print(f"{r['count']:6} total={r['total']:.2f}s max={r['max'] * 1000:.0f}ms avgExamined={r['avg_examined']:7} | {r['shape'][:150]}")
