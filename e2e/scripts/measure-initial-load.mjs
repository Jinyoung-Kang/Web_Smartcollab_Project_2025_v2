// v1(태그 v1.0.0) vs v2 로그인 화면 초기 로딩 비용 측정
// 사용법: node scripts/measure-initial-load.mjs [v2URL=http://localhost:8080/login] [runs=5]
// - v1: 저장소의 v1.0.0 태그에서 정적 파일(index.html, js/)을 꺼내 로컬 정적 서버로 제공.
//       React·Babel·Tailwind 등은 v1 그대로 외부 CDN 에서 받습니다.
// - 측정: 새 브라우저 컨텍스트(캐시 없음)로 접속해 로그인 버튼이 보일 때까지
//   ① 요청 수 ② 전송 바이트(압축 후) ③ 메인 스레드 스크립트 실행 시간(CDP ScriptDuration) ④ 경과 시간
//   ①~③은 네트워크 속도와 무관한 지표, ④는 v1 이 인터넷 CDN 을 쓰므로 참고용입니다.
//   전송 바이트는 JS·CSS·폰트로 나눕니다 (v2 는 한글 웹폰트를 쓰고 v1 은 시스템 폰트를 씀).
import { chromium } from '@playwright/test'
import { execSync } from 'node:child_process'
import { createServer } from 'node:http'
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, extname, join } from 'node:path'

const v2Url = process.argv[2] ?? 'http://localhost:8080/login'
const runs = Number(process.argv[3] ?? 5)
const repo = join(import.meta.dirname, '..', '..')

// 1) v1 정적 파일 추출
const v1Dir = mkdtempSync(join(tmpdir(), 'smartcollab-v1-'))
const files = execSync('git ls-tree -r --name-only v1.0.0 src/main/resources/static', { cwd: repo }).toString().trim().split('\n')
for (const f of files) {
  const out = join(v1Dir, f.replace('src/main/resources/static/', ''))
  mkdirSync(dirname(out), { recursive: true })
  writeFileSync(out, execSync(`git show v1.0.0:${f}`, { cwd: repo, maxBuffer: 20 * 1024 * 1024 }))
}
const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.png': 'image/png', '.ico': 'image/x-icon' }
const server = createServer((req, res) => {
  const path = join(v1Dir, req.url === '/' ? 'index.html' : decodeURIComponent(req.url.split('?')[0]))
  if (!existsSync(path)) { res.writeHead(404); return res.end() }
  res.writeHead(200, { 'content-type': types[extname(path)] ?? 'application/octet-stream' })
  res.end(readFileSync(path))
}).listen(0)
const v1Url = `http://localhost:${server.address().port}/`

async function measure(url, readySelector) {
  const browser = await chromium.launch({ channel: 'chrome' })
  const context = await browser.newContext()
  const page = await context.newPage()
  const cdp = await context.newCDPSession(page)
  await cdp.send('Network.enable')
  await cdp.send('Performance.enable')
  let requests = 0
  const typeOf = new Map()
  const byType = { script: 0, stylesheet: 0, font: 0, other: 0 }
  cdp.on('Network.requestWillBeSent', () => requests++)
  cdp.on('Network.responseReceived', (e) => {
    const t = e.type === 'Script' ? 'script' : e.type === 'Stylesheet' ? 'stylesheet' : e.type === 'Font' ? 'font' : 'other'
    typeOf.set(e.requestId, t)
  })
  cdp.on('Network.loadingFinished', (e) => { byType[typeOf.get(e.requestId) ?? 'other'] += e.encodedDataLength })
  const start = Date.now()
  await page.goto(url)
  await page.locator(readySelector).first().waitFor({ state: 'visible', timeout: 60_000 })
  const elapsed = Date.now() - start
  await page.waitForTimeout(300)
  const metrics = Object.fromEntries((await cdp.send('Performance.getMetrics')).metrics.map((m) => [m.name, m.value]))
  await browser.close()
  const kb = (n) => Math.round(n / 1024)
  const total = byType.script + byType.stylesheet + byType.font + byType.other
  return {
    requests,
    totalKb: kb(total),
    jsKb: kb(byType.script),
    cssKb: kb(byType.stylesheet),
    fontKb: kb(byType.font),
    scriptMs: Math.round(metrics.ScriptDuration * 1000),
    elapsedMs: elapsed,
  }
}

const median = (xs) => [...xs].sort((a, b) => a - b)[Math.floor(xs.length / 2)]
const summarize = (rs) => Object.fromEntries(Object.keys(rs[0]).map((k) => [k, median(rs.map((r) => r[k]))]))

const results = { v1: [], v2: [] }
for (let i = 0; i < runs; i++) {
  results.v1.push(await measure(v1Url, 'button[type=submit]:has-text("로그인")'))
  results.v2.push(await measure(v2Url, 'button[type=submit]:has-text("로그인")'))
}
server.close()
const report = { runs, measuredAt: new Date().toISOString(), v1: summarize(results.v1), v2: summarize(results.v2), raw: results }
console.log(JSON.stringify(report, null, 2))
writeFileSync(join(repo, 'docs', 'measurements', 'initial-load.json'), JSON.stringify(report, null, 2) + '\n')
