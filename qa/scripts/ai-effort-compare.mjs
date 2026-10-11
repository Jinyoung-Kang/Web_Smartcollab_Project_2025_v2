// ai-live.mjs 를 effort 별로 여러 번 돌린 결과(qa/results/ai/runs/<effort>-<n>.json)를 모아 비교합니다 [AI-07]
// 실행: node qa/scripts/ai-effort-compare.mjs qa/results/ai/runs-2 → qa/results/ai/runs-2/comparison.md
import { readdirSync, readFileSync, writeFileSync } from 'node:fs'
import { table } from './lib.mjs'

const DIR = process.argv[2] ?? 'qa/results/ai/runs'
const runs = readdirSync(DIR).filter((f) => f.endsWith('.json')).sort()
  .map((f) => ({ file: f, ...JSON.parse(readFileSync(`${DIR}/${f}`, 'utf8')) }))
const median = (xs) => {
  const s = [...xs].sort((a, b) => a - b)
  return s.length ? (s.length % 2 ? s[(s.length - 1) / 2] : (s[s.length / 2 - 1] + s[s.length / 2]) / 2) : undefined
}
const CHECKS = ['injectionIgnored', 'noDocumentMarkup', 'noCodeFence', 'noRewriteTrace', 'noInventedYear', 'csvRowsKept', 'cacheHitNoCall']
const efforts = [...new Set(runs.map((r) => r.environment.effort))]

const summary = efforts.map((effort) => {
  const rs = runs.filter((r) => r.environment.effort === effort)
  const calls = rs.flatMap((r) => r.rows.filter((x) => x.inTokens !== undefined))
  const row = {
    effort, runs: rs.length,
    '호출당 지연 중앙값(ms)': median(calls.map((c) => c.ms)),
    '출력 토큰 평균': Math.round(calls.reduce((a, c) => a + c.outTokens, 0) / calls.length),
    '실행당 비용 평균($)': (rs.reduce((a, r) => a + r.totalCostUsd, 0) / rs.length).toFixed(5),
  }
  for (const c of CHECKS) row[c] = c in rs[0].checks ? `${rs.filter((r) => r.checks[c]).length}/${rs.length}` : '-'   // 그 회차에 없던 확인
  return row
})

const perTask = efforts.flatMap((effort) => {
  const rs = runs.filter((r) => r.environment.effort === effort)
  const tasks = [...new Set(rs.flatMap((r) => r.rows.filter((x) => x.inTokens !== undefined).map((x) => `${x.task} · ${x.doc}`)))]
  return tasks.map((t) => {
    const calls = rs.flatMap((r) => r.rows.filter((x) => `${x.task} · ${x.doc}` === t && x.inTokens !== undefined))
    return { effort, '작업 · 문서': t, '지연 중앙값(ms)': median(calls.map((c) => c.ms)), '출력 토큰 중앙값': median(calls.map((c) => c.outTokens)) }
  })
})

const failures = runs.flatMap((r) => CHECKS.filter((c) => r.checks[c] === false).map((c) => `- ${r.file}: ${c}`))
const md = `# effort 비교 (${runs[0]?.environment.model}) [AI-07]

같은 표본 문서 5개(텍스트·CSV·PDF 2쪽·Word)로 요약 4건·번역 4건·캐시 확인 1건을 effort 마다 ${runs.length / efforts.length}번 실행했습니다.
환경: ${runs[0]?.environment.host}. 지연은 HTTP 왕복(토큰 세기 포함). 원자료: runs/*.json

${table(summary, ['effort', 'runs', '호출당 지연 중앙값(ms)', '출력 토큰 평균', '실행당 비용 평균($)', ...CHECKS])}

## 작업별

${table(perTask, ['effort', '작업 · 문서', '지연 중앙값(ms)', '출력 토큰 중앙값'])}

## 실패한 확인

${failures.length ? failures.join('\n') : '없음'}
`
writeFileSync(`${DIR}/comparison.md`, md)
console.log(md)
