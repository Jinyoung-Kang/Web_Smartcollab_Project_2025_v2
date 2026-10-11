// 번역 결과 첫 줄에 (옮긴) 파일 이름이 붙는지 반복 확인 [AI-10] — 실제 Claude 호출(번역 1건 약 $0.0002).
// 데모 문서와 같은 이름·내용("주간 회의 메모.txt")을 폴더마다 하나씩 올려(결과 캐시를 피함) 일본어로 N번 번역합니다.
// 실행: QA_BASE_URL=http://localhost:8090 REPEAT=10 VARIANT=after node qa/scripts/ai-translate-title.mjs → qa/results/ai/title-<VARIANT>.json
import { mkdirSync, writeFileSync } from 'node:fs'
import { Client } from './lib.mjs'

const REPEAT = Number(process.env.REPEAT ?? 10)
const VARIANT = process.env.VARIANT ?? 'current'
const MEMO = '주간 회의 메모 (예시 문서)\n- 체크리스트 1차 완료\n- 화면 스케치 피드백 반영 예정\n- 다음 회의에서 외부 공유 규칙 확정\n'

const U = await new Client('ai-title').signup()
const results = []
for (let i = 0; i < REPEAT; i++) {
  const folder = await U.createFolder(U.me.rootFolderId, `f${i}`)
  const up = await U.upload(folder.id, '주간 회의 메모.txt', MEMO, 'text/plain')
  const r = await U.post(`/api/files/${up.data.id}/translation?target=JA`)
  const text = String(r.data?.text ?? '')
  const first = text.trim().split('\n')[0]
  const titled = /\.txt\s*$/.test(first)
  results.push({ i, status: r.status, ms: Math.round(r.ms), titled, first, text })
  console.log(i, r.status, titled ? `파일 이름: ${first}` : first)
}
const titled = results.filter((r) => r.titled).length
mkdirSync('qa/results/ai', { recursive: true })
writeFileSync(`qa/results/ai/title-${VARIANT}.json`, JSON.stringify({ variant: VARIANT, repeat: REPEAT, titled, results }, null, 2))
console.log(`${VARIANT}: ${titled}/${REPEAT} 번 첫 줄에 파일 이름`)
