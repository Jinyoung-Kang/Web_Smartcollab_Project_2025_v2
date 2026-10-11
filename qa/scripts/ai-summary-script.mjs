// 한국어 요약에 일본어·한자가 섞이는지 반복 확인 [AI-11] — 실제 Claude 호출(요약 1건 약 $0.0006). 2쪽 PDF 를 매번 새 파일로 N번 요약합니다.
// 실행: QA_BASE_URL=http://localhost:8090 N=10 node qa/scripts/ai-summary-script.mjs
import { Client } from './lib.mjs'
import { pdf, pdfHtml } from './ai-samples.mjs'
const FOREIGN = /[぀-ヿ一-鿿]/ // 가나·한자
const body = Buffer.from(await pdf(pdfHtml))
const U = await new Client('mixed').signup()
let hit = 0
const N = Number(process.env.N ?? 10)
for (let i = 0; i < N; i++) {
  const up = await U.upload(U.me.rootFolderId, `보고서-${i}.pdf`, body, 'application/pdf')
  const r = await U.post(`/api/files/${up.data.id}/summary`)
  const text = [r.data?.summary ?? '', ...(r.data?.sentences ?? [])].join(' ')
  const m = text.match(new RegExp(`.{0,12}${FOREIGN.source}+.{0,6}`))
  if (m) hit++
  console.log(i, r.status, m ? `섞임: ${m[0]}` : '정상')
}
console.log(`foreign-script ${hit}/${N}`)
