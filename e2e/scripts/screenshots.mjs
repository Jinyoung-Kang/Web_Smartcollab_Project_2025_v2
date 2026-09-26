// 문서·포트폴리오용 화면 캡처 (데모 데이터가 막 만들어진 상태의 스택에서 실행)
// 사용법: node scripts/screenshots.mjs [baseURL=http://localhost:8080] [outDir=../docs/images]
import { chromium } from '@playwright/test'
import { execSync } from 'node:child_process'
import { createServer } from 'node:http'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, extname, join } from 'node:path'

const base = process.argv[2] ?? 'http://localhost:8080'
const out = process.argv[3] ?? join(import.meta.dirname, '..', '..', 'docs', 'images')
const password = process.env.DEMO_PASSWORD ?? 'demo1234!'
mkdirSync(out, { recursive: true })

const browser = await chromium.launch({ channel: 'chrome' })

async function session(username, viewport = { width: 1440, height: 900 }) {
  const context = await browser.newContext({ viewport, deviceScaleFactor: 2, locale: 'ko-KR', timezoneId: 'Asia/Seoul' })
  const page = await context.newPage()
  await page.goto(`${base}/login`)
  await page.getByLabel('아이디').fill(username)
  await page.getByLabel('비밀번호', { exact: true }).fill(password)
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await page.waitForURL(/\/drive/)
  return page
}

const shot = async (page, name, options = {}) => {
  await page.waitForTimeout(400)
  await page.screenshot({ path: join(out, `${name}.png`), ...options })
  console.log('saved', name)
}

// 1) 로그인 화면 (데모 계정 안내 포함)
{
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2 })
  const page = await context.newPage()
  await page.goto(`${base}/login`)
  await page.getByText('체험용 데모 계정').waitFor()
  await shot(page, 'login')
  await context.close()
}

// 두 번째 사용자가 접속해 있어야 접속 표시·실시간 화면이 자연스럽습니다.
const other = await session('demo2')
await other.getByRole('link', { name: /SmartCollab 데모 팀/ }).click()
await other.waitForURL(/\/folders\//)

const page = await session('demo1')

// 2) 팀 드라이브 + 채팅 패널
await page.getByRole('link', { name: /SmartCollab 데모 팀/ }).click()
await page.waitForURL(/\/folders\//)
await page.getByText('2명 접속 중').waitFor()
await shot(page, 'team-drive')

// 3) 회의록 폴더 → 선택 작업 바
await page.getByRole('button', { name: '회의록', exact: true }).click()
await page.getByLabel('킥오프 회의.md 선택').check()
await shot(page, 'selection-toolbar')

// 4) 버전 기록 (서명 유효/무효)
await page.getByRole('toolbar').getByRole('button', { name: '버전 기록' }).click()
await page.getByRole('dialog', { name: '버전 기록' }).getByText('현재 버전').waitFor()
await shot(page, 'version-history')
await page.keyboard.press('Escape')

// 5) 편집기 + 핵심 문장 요약
await page.getByLabel('킥오프 회의.md 선택').uncheck()
await page.getByRole('button', { name: '킥오프 회의.md', exact: true }).click()
await page.getByRole('button', { name: '편집기로 열기' }).click()
await page.getByRole('button', { name: /핵심 문장/ }).click()
await page.getByText('핵심 문장 (추출 요약)').waitFor()
await shot(page, 'editor-summary')

// 6) 기획 폴더 → 공유 링크 대화상자
await page.goto(`${base}/teams/1`)
await page.waitForURL(/\/folders\//)
await page.getByRole('button', { name: '기획', exact: true }).click()
await page.getByLabel('프로젝트 개요.md 선택').check()
await page.getByRole('toolbar').getByRole('button', { name: '공유' }).click()
await page.getByRole('dialog', { name: '공유 링크' }).getByText('만든 링크').waitFor()
await shot(page, 'share-dialog')
const sharePath = (await page.getByRole('dialog').locator('p.font-mono').first().textContent()).trim()
await page.keyboard.press('Escape')

// 7) 멤버 탭 + 권한 변경
await page.getByRole('tab', { name: /멤버/ }).click()
await page.getByRole('button', { name: '박서연 관리' }).click()
await page.getByRole('menuitem', { name: '권한 변경' }).click()
await shot(page, 'permissions')
await page.keyboard.press('Escape')

// 8) 알림 (다른 팀 초대)
await page.getByRole('button', { name: /알림 \d+개 읽지 않음/ }).click()
await page.getByRole('button', { name: '수락' }).waitFor()
await shot(page, 'notifications')
await page.keyboard.press('Escape')

// 9) 검색
await page.getByRole('searchbox').or(page.getByPlaceholder(/파일 검색/)).first().fill('회의')
await page.keyboard.press('Enter')
await page.getByText(/검색 결과/).waitFor()
await shot(page, 'search')

// 10) 휴지통
await page.getByRole('link', { name: '휴지통' }).click()
await page.getByText('지난 초안.txt').waitFor()
await shot(page, 'trash')

// 11) 공유 링크 페이지 (로그인 없음)
{
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2 })
  const anon = await context.newPage()
  await anon.goto(sharePath)
  await anon.getByText('프로젝트 개요.md').waitFor()
  await shot(anon, 'share-page')
  await context.close()
}

// 12) 모바일 화면
{
  const mobile = await session('demo3', { width: 390, height: 844 })
  await mobile.goto(`${base}/teams/1`)
  await mobile.waitForURL(/\/folders\//)
  await shot(mobile, 'mobile-drive')
  await mobile.getByRole('button', { name: /팀 채팅·멤버/ }).click()
  await mobile.getByText(/명 접속 중/).waitFor()
  await shot(mobile, 'mobile-chat')
}

// 13) v1 로그인 화면 (비교용, 태그 v1.0.0 정적 파일 + 외부 CDN)
{
  const repo = join(import.meta.dirname, '..', '..')
  const dir = mkdtempSync(join(tmpdir(), 'v1-'))
  const files = execSync('git ls-tree -r --name-only v1.0.0 src/main/resources/static', { cwd: repo }).toString().trim().split('\n')
  for (const f of files) {
    const target = join(dir, f.replace('src/main/resources/static/', ''))
    mkdirSync(dirname(target), { recursive: true })
    writeFileSync(target, execSync(`git show v1.0.0:${f}`, { cwd: repo, maxBuffer: 20 * 1024 * 1024 }))
  }
  const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.png': 'image/png' }
  const server = createServer((req, res) => {
    const p = join(dir, req.url === '/' ? 'index.html' : decodeURIComponent(req.url.split('?')[0]))
    if (!existsSync(p)) { res.writeHead(404); return res.end() }
    res.writeHead(200, { 'content-type': types[extname(p)] ?? 'application/octet-stream' })
    res.end(readFileSync(p))
  }).listen(0)
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2 })
  const v1 = await context.newPage()
  await v1.goto(`http://localhost:${server.address().port}/`)
  await v1.getByText('확인하고 시작하기').waitFor({ timeout: 30_000 })
  await shot(v1, 'v1-login')
  server.close()
}

await browser.close()
