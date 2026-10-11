// 한국어 → 일본어·중국어 번역이 생각을 결과에 섞어 쓰는지 반복 확인 [AI-07] — 실제 Claude 호출(번역 1건 약 $0.0002).
// 할일.csv → 일본어, 입사 안내.docx → 중국어를 매번 새 파일로 올려(결과 캐시를 피함) N번씩 번역합니다.
// 실행: QA_BASE_URL=http://localhost:8090 REPEAT=10 VARIANT=t-medium node qa/scripts/ai-translate-repeat.mjs → qa/results/ai/translate-<VARIANT>.json
import { mkdirSync, writeFileSync } from 'node:fs'
import { Client } from './lib.mjs'
import { CSV, docx, docxParagraphs } from './ai-samples.mjs'

const REPEAT = Number(process.env.REPEAT ?? 10)
const VARIANT = process.env.VARIANT ?? 'current'
// 일본어·중국어 결과에 영어 문장(생각·정정)이 섞임
const TRACE = /\b(wait|i must|i need|let me|translat\w*|correct output|actually)\b/i

const U = await new Client('ai-translate').signup()
const cases = [
  { name: '할일.csv', target: 'JA', body: CSV, type: 'text/csv', rows: CSV.trim().split('\n').length },
  { name: '입사 안내.docx', target: 'ZH', body: docx(docxParagraphs), type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' },
]
const results = []
for (let i = 0; i < REPEAT; i++) {
  for (const c of cases) {
    const up = await U.upload(U.me.rootFolderId, `${i}-${c.name}`, c.body, c.type)
    const r = await U.post(`/api/files/${up.data.id}/translation?target=${c.target}`)
    const text = String(r.data?.text ?? '')
    const trace = TRACE.test(text)
    const rowsKept = c.rows === undefined || text.trim().split('\n').length === c.rows
    results.push({ i, doc: c.name, target: c.target, status: r.status, ms: Math.round(r.ms), trace, rowsKept, text })
    console.log(i, c.name, c.target, r.status, Math.round(r.ms) + 'ms', trace ? '흔적 있음' : '', rowsKept ? '' : '행 수 다름')
  }
}
const bad = results.filter((r) => r.status !== 200 || r.trace || !r.rowsKept).length
const ms = results.map((r) => r.ms).sort((a, b) => a - b)
mkdirSync('qa/results/ai', { recursive: true })
writeFileSync(`qa/results/ai/translate-${VARIANT}.json`, JSON.stringify({ variant: VARIANT, repeat: REPEAT, calls: results.length, bad, medianMs: ms[Math.floor(ms.length / 2)], results }, null, 2))
console.log(`${VARIANT}: ${results.length}건 중 ${bad}건 문제, 지연 중앙값 ${ms[Math.floor(ms.length / 2)]}ms`)
