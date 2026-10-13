// [AI-12] PDF 번역 실측(과금) — 같은 PDF 를 매번 새로 올려(결과 캐시를 피함) 대상 언어마다 번역합니다. 임시 스택 sc-ai(8090)에서만.
// 사용: node qa/scripts/ai-translate-pdf.mjs <PDF 경로> <결과.json> KO KO EN JA …   (BASE 기본 http://localhost:8090)
// 공개 저장소에 남기므로 결과 글은 저장하지 않고 길이·한글 비율·결과 유형만 적습니다.
import { readFileSync, writeFileSync } from 'node:fs'
import { basename } from 'node:path'

const base = process.env.BASE ?? 'http://localhost:8090'
const [pdfPath, out, ...targets] = process.argv.slice(2)
if (!pdfPath || !out || targets.length === 0) throw new Error('사용: node qa/scripts/ai-translate-pdf.mjs <PDF> <결과.json> KO …')
const pdf = readFileSync(pdfPath)
const cookies = {}
const jar = () => Object.entries(cookies).map(([k, v]) => `${k}=${v}`).join('; ')
const keep = (r) => { for (const c of r.headers.getSetCookie()) { const [kv] = c.split(';'); const i = kv.indexOf('='); cookies[kv.slice(0, i)] = kv.slice(i + 1) } }
async function req(method, path, body, headers = {}) {
  if (method !== 'GET' && !cookies['XSRF-TOKEN']) keep(await fetch(base + '/api/auth/csrf', { headers: { cookie: jar() } }))
  const r = await fetch(base + path, { method, body, headers: { cookie: jar(), 'X-XSRF-TOKEN': cookies['XSRF-TOKEN'] ?? '', ...headers } })
  keep(r)
  const t = await r.text()
  let d = t
  try { d = JSON.parse(t) } catch { /* 본문이 JSON 이 아님 */ }
  return { status: r.status, data: d }
}
const isJson = (t) => { try { const v = JSON.parse(t); return typeof v === 'object' && v !== null } catch { return false } }
const kind = (status, text) => status !== 200 ? '실패' : isJson(text.trim()) ? 'JSON(문서 아님)' : text.length < 500 ? '짧음' : '문서 전체'

const u = 'aipdf' + Math.random().toString(36).slice(2, 7), pw = 'Pw1-' + Math.random().toString(36).slice(2)
const s = await req('POST', '/api/auth/signup', JSON.stringify({ username: u, password: pw, passwordConfirm: pw, name: '실측' }), { 'content-type': 'application/json' })
if (s.status !== 201) throw new Error(`가입 ${s.status}`)
const results = []
for (const [i, target] of targets.entries()) {
  const form = new FormData()
  form.append('file', new Blob([pdf], { type: 'application/pdf' }), `sample-${i}.pdf`)
  const up = await req('POST', `/api/files/upload?folderId=${s.data.rootFolderId}`, form)
  const started = Date.now()
  const r = await req('POST', `/api/files/${up.data.id}/translation?target=${target}`)
  const text = r.data?.text ?? ''
  results.push({ run: i + 1, target, status: r.status, ms: Date.now() - started, kind: kind(r.status, text), chars: text.length,
    hangulRatio: text ? +((text.match(/[가-힣]/g) ?? []).length / text.length).toFixed(2) : 0, error: r.status >= 400 ? r.data?.detail : undefined })
  console.log(JSON.stringify(results.at(-1)))
}
writeFileSync(out, JSON.stringify({ at: new Date().toISOString(), file: `${basename(pdfPath).replace(/[^.]+(?=\.pdf$)/i, 'sample')} (${pdf.length} bytes)`, results }, null, 2) + '\n')
