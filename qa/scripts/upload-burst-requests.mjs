// 업로드 묶음 동안 화면이 서버로 보내는 다시 받기 요청 수를 잽니다 [M-9] — 로컬 스택에서만.
// 실행: QA_BASE_URL=http://localhost:8090 DEMO_PASSWORD=... node qa/scripts/upload-burst-requests.mjs [파일 수]
// 체험 계정 demo1 로 로그인해 내 드라이브에 작은 텍스트 파일 N 개를 한 번에 올리고, 업로드(POST) 외의 GET 요청을 경로별로 셉니다.
import { createRequire } from 'node:module'

// Playwright 는 e2e/ 에 설치되어 있습니다
const { chromium } = createRequire(new URL('../../e2e/package.json', import.meta.url))('@playwright/test')

const BASE = process.env.QA_BASE_URL ?? 'http://localhost:8090'
if (!/^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(BASE)) throw new Error(`로컬 주소에만 씁니다: ${BASE}`)
const N = Number(process.argv[2] ?? 100)
const browser = await chromium.launch()
const page = await browser.newPage()
await page.goto(`${BASE}/login`)
await page.getByLabel('아이디').fill('demo1')
await page.getByLabel('비밀번호', { exact: true }).fill(process.env.DEMO_PASSWORD ?? 'ci-demo-1234')
await page.getByRole('button', { name: '로그인', exact: true }).click()
await page.waitForURL(/\/drive/)
await page.waitForLoadState('networkidle')

const counts = {}
page.on('request', (r) => {
  if (r.method() !== 'GET' || !r.url().includes('/api/')) return
  const path = new URL(r.url()).pathname.replace(/\/\d+/g, '/{id}')
  counts[path] = (counts[path] ?? 0) + 1
})
const stamp = Date.now()
const files = Array.from({ length: N }, (_, i) => ({ name: `burst-${stamp}-${i}.txt`, mimeType: 'text/plain', buffer: Buffer.from(`burst ${i}`) }))
const started = Date.now()
await page.locator('input[type=file][multiple]').setInputFiles(files)
await page.getByRole('region', { name: '업로드 진행 상황' }).getByText(`업로드 완료 (${N}개)`).waitFor({ timeout: 300_000 })
await page.waitForLoadState('networkidle')
await page.waitForTimeout(1500)
const total = Object.values(counts).reduce((a, b) => a + b, 0)
console.log(JSON.stringify({ files: N, seconds: (Date.now() - started) / 1000, getRequests: total, byPath: counts }, null, 2))
await browser.close()
