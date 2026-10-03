// 항목이 많은 폴더에서 드라이브 화면의 렌더링 비용 측정 [PERF-02]
// 사용법: DEMO_PASSWORD=... node scripts/measure-large-folder.mjs [baseURL=http://localhost:8080] [runs=3]
// - 데모 계정(demo1)으로 로그인한 뒤, 내 드라이브의 폴더 목록 API 응답만 가짜로 1,000·5,000개로 늘려 화면을 엽니다
//   (서버 쪽 비용은 별도로 측정: 5,000개 목록 API 76~86ms — 병목 아님).
// - 측정: ① 첫 표시(주소 이동 → 첫 묶음의 마지막 행이 그려질 때까지) ② '크기' 정렬 클릭 ③ '모두 선택' 클릭
//   (② ③은 클릭부터 다음 프레임까지) ④ JS 힙 사용량. 결과는 docs/measurements/large-folder.json
// - 폴더 목록이 묶음으로 나뉜 뒤(IMP-02) 서버는 한 번에 최대 1,000개를 주고 정렬도 서버가 합니다. 이 스크립트는 받은 항목을
//   그리는 비용만 보려고 n 개를 한 응답에 담아 주며, 정렬 클릭은 같은 가짜 응답을 다시 받아 그리는 시간까지 포함합니다.
import { chromium } from '@playwright/test'
import { writeFileSync } from 'node:fs'
import { join } from 'node:path'

const base = process.argv[2] ?? 'http://localhost:8080'
const runs = Number(process.argv[3] ?? 3)
const password = process.env.DEMO_PASSWORD ?? 'demo1234!'
const FIRST_BATCH = 200   // FileTable 의 RENDER_BATCH

const browser = await chromium.launch({ channel: 'chrome' })
const page = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage()
await page.goto(`${base}/login`)
await page.getByLabel('아이디').fill('demo1')
await page.getByLabel('비밀번호', { exact: true }).fill(password)
await page.getByRole('button', { name: '로그인', exact: true }).click()
await page.waitForURL(/\/drive/)
const me = await (await page.request.get(`${base}/api/auth/me`)).json()
const real = await (await page.request.get(`${base}/api/folders/${me.rootFolderId}`)).json()
// 목록 요청에는 정렬·묶음 매개변수가 붙으므로 경로로 고릅니다 [IMP-02]
const isRootListing = (url) => url.pathname === `/api/folders/${me.rootFolderId}`
const sample = real.items.find((i) => i.type === 'file')

const clickTime = (code) => page.evaluate(async (c) => {
  const t = performance.now()
  new Function(c)()
  await new Promise((resolve) => requestAnimationFrame(() => setTimeout(resolve, 0)))
  return Math.round(performance.now() - t)
}, code)

const results = []
for (let run = 1; run <= runs; run++) {
  for (const n of [1000, 5000]) {
    const items = Array.from({ length: n }, (_, i) => ({ ...sample, id: 900000 + i, name: `보고서-${String(i).padStart(5, '0')}.txt` }))
    const body = JSON.stringify({ ...real, items, itemCount: n, nextCursor: null })
    await page.route(isRootListing, (r) => r.fulfill({ status: 200, contentType: 'application/json', body }))
    const t0 = Date.now()
    await page.goto(`${base}/drive`)
    await page.getByText(`보고서-${String(Math.min(n, FIRST_BATCH) - 1).padStart(5, '0')}.txt`).waitFor({ state: 'attached' })
    const firstRenderMs = Date.now() - t0
    const renderedRows = await page.locator('tbody tr[data-row]').count()
    const sortMs = await clickTime(`[...document.querySelectorAll('th button')].find((b) => b.textContent.includes('크기')).click()`)
    const selectAllMs = await clickTime(`document.querySelector('thead input[type=checkbox]').click()`)
    const heapMb = await page.evaluate(() => Math.round((performance.memory?.usedJSHeapSize ?? 0) / 1048576))
    results.push({ run, items: n, jsonKb: Math.round(body.length / 1024), renderedRows, firstRenderMs, sortMs, selectAllMs, heapMb })
    console.log(results.at(-1))
    await page.unroute(isRootListing)
  }
}
await browser.close()

const out = join(import.meta.dirname, '..', '..', 'docs', 'measurements', 'large-folder.json')
writeFileSync(out, JSON.stringify({
  measuredAt: new Date().toISOString(),
  method: 'API 응답만 가짜로 늘림, Chrome, 1440×900. firstRenderMs 는 주소 이동부터 첫 묶음 표시까지',
  // 변경 전 값은 같은 방법으로 코드 변경 전(a11b576)에 1회 잰 값입니다 — 이전 코드로는 다시 잴 수 없어 기록으로 남깁니다.
  before: [
    { items: 1000, renderedRows: 1000, firstRenderMs: 410, sortMs: 99, selectAllMs: 44, heapMb: 39 },
    { items: 5000, renderedRows: 5000, firstRenderMs: 1454, sortMs: 190, selectAllMs: 202, heapMb: 123 },
  ],
  after: results,
}, null, 2) + '\n')
console.log('saved', out)
