// 요약이 문서에 없는 연도를 지어내는지 반복 확인 [AI-07] — 실제 Claude 호출(요약 1건 약 $0.0004).
// 회의록(연도 없이 "9월 24일"·"3분기")을 매번 새 파일로 올려(결과 캐시를 피함) N번 요약합니다.
// 실행: QA_BASE_URL=http://localhost:8090 REPEAT=10 VARIANT=v1 node qa/scripts/ai-summary-dates.mjs → qa/results/ai/dates-<VARIANT>.json
import { mkdirSync, writeFileSync } from 'node:fs'
import { Client } from './lib.mjs'
import { MEETING } from './ai-samples.mjs'

const REPEAT = Number(process.env.REPEAT ?? 10)
const VARIANT = process.env.VARIANT ?? 'current'
const YEAR = /(19|20)\d\d/

const U = await new Client('ai-dates').signup()
const results = []
for (let i = 0; i < REPEAT; i++) {
  const up = await U.upload(U.me.rootFolderId, `회의록-${i}.md`, MEETING, 'text/markdown')
  const r = await U.post(`/api/files/${up.data.id}/summary`)
  const text = [r.data?.summary ?? '', ...(r.data?.sentences ?? [])].join(' ')
  results.push({ status: r.status, ms: Math.round(r.ms), inventedYear: YEAR.test(text), year: text.match(/.{0,10}(19|20)\d\d.{0,10}/)?.[0], summary: r.data?.summary })
  console.log(i, r.status, YEAR.test(text) ? `연도: ${text.match(/.{0,10}(19|20)\d\d.{0,10}/)[0]}` : '연도 없음')
}
const invented = results.filter((r) => r.inventedYear).length
mkdirSync('qa/results/ai', { recursive: true })
writeFileSync(`qa/results/ai/dates-${VARIANT}.json`, JSON.stringify({ variant: VARIANT, repeat: REPEAT, invented, results }, null, 2))
console.log(`${VARIANT}: ${invented}/${REPEAT} 번 연도를 지어냄`)
