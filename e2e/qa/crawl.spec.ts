// 화면 크롤 — 모든 화면에서 보이는 버튼·탭·링크·체크박스·메뉴 항목을 차례로 눌러 보고,
// 콘솔 오류·페이지 오류·실패한 요청·짧은 시간에 같은 요청이 두 번 이상 나간 경우·CSP 위반을 모읍니다.
// 결과: qa/results/ui-crawl.json, 스크린샷 qa/results/screens/
import { test, expect, type Page, type Locator } from '@playwright/test'
import { mkdirSync, writeFileSync } from 'node:fs'
import { seed, type Seed } from './seed'

const OUT = new URL('../../qa/results/', import.meta.url).pathname
mkdirSync(`${OUT}screens`, { recursive: true })

interface Issue { kind: string; page: string; detail: string; after?: string }
const issues: Issue[] = []
const clicked: Record<string, string[]> = {}
let lastAction = '(처음)'
let data: Seed

function watch(page: Page, label: string) {
  const recent = new Map<string, number>()
  page.on('console', (m) => {
    if (m.type() === 'error' || m.type() === 'warning') issues.push({ kind: `console.${m.type()}`, page: `${label} ${page.url()}`, detail: m.text().slice(0, 300), after: lastAction })
  })
  page.on('pageerror', (e) => issues.push({ kind: 'pageerror', page: `${label} ${page.url()}`, detail: String(e).slice(0, 300), after: lastAction }))
  page.on('requestfailed', (r) => {
    const f = r.failure()?.errorText ?? ''
    if (/ERR_ABORTED/.test(f) && /\/(download|view)|\/ws/.test(r.url())) return // 내려받기·미리보기 탐색 취소, 웹소켓 종료
    issues.push({ kind: 'requestfailed', page: `${label} ${page.url()}`, detail: `${r.method()} ${r.url()} ${f}`, after: lastAction })
  })
  page.on('response', (r) => {
    const u = new URL(r.url())
    if (!u.pathname.startsWith('/api/')) return
    if (r.status() >= 400) issues.push({ kind: `http.${r.status()}`, page: `${label} ${page.url()}`, detail: `${r.request().method()} ${u.pathname}${u.search}`, after: lastAction })
  })
  page.on('request', (r) => {
    const u = new URL(r.url())
    if (!u.pathname.startsWith('/api/')) return
    const key = `${r.method()} ${u.pathname}${u.search}`
    const now = Date.now()
    const prev = recent.get(key)
    if (prev && now - prev < 400) issues.push({ kind: 'duplicate-request', page: `${label} ${page.url()}`, detail: `${key} (${now - prev}ms 간격)`, after: lastAction })
    recent.set(key, now)
  })
}

async function settle(page: Page) {
  await page.waitForLoadState('networkidle', { timeout: 5000 }).catch(() => {})
  await page.waitForTimeout(150)
}

async function login(page: Page, username: string, password: string) {
  await page.goto('/login')
  await page.getByLabel('아이디').fill(username)
  await page.getByLabel('비밀번호', { exact: true }).fill(password)
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page).toHaveURL(/\/drive/)
}

const CLICKABLE = 'button:visible, [role="tab"]:visible, a[href]:visible, input[type="checkbox"]:visible, [role="menuitem"]:visible, select:visible'
// 세션을 끝내거나(로그아웃·탈퇴) 크롤 대상을 통째로 지우는 것은 따로 확인합니다
const SKIP = /로그아웃|회원 탈퇴|팀 삭제|팀 나가기|휴지통 비우기|영구 삭제|모두 삭제|대화 기록 지우기|내려받기|다운로드/

async function nameOf(el: Locator) {
  return ((await el.getAttribute('aria-label')) ?? (await el.innerText().catch(() => '')) ?? (await el.getAttribute('title')) ?? '').trim().replace(/\s+/g, ' ').slice(0, 40)
}

/** 화면의 조작 요소를 하나씩 누릅니다. 메뉴·대화상자가 열리면 그 안의 항목도 한 단계 더 눌러 봅니다. */
async function crawl(page: Page, path: string, label: string) {
  clicked[label] = []
  await page.goto(path)
  await settle(page)
  await page.screenshot({ path: `${OUT}screens/${label}.png`, fullPage: true })
  const total = await page.locator(CLICKABLE).count()
  for (let i = 0; i < Math.min(total, 80); i++) {
    if (new URL(page.url()).pathname + new URL(page.url()).search !== path) {
      await page.goto(path)
      await settle(page)
    }
    const el = page.locator(CLICKABLE).nth(i)
    if (!(await el.count())) break
    const name = await nameOf(el)
    if (!name || SKIP.test(name)) continue
    const href = await el.getAttribute('href')
    if (href && /^https?:/.test(href) && !href.includes('localhost')) continue
    lastAction = `${label}: "${name}"`
    try {
      await el.click({ timeout: 2000 })
    } catch {
      continue
    }
    clicked[label].push(name)
    await settle(page)
    // 한 단계 더: 열린 메뉴의 항목, 열린 대화상자의 버튼
    const inner = page.locator('[role="menu"] [role="menuitem"]:visible, [role="dialog"] button:visible, [role="dialog"] [role="tab"]:visible')
    const innerCount = await inner.count()
    for (let j = 0; j < Math.min(innerCount, 12); j++) {
      const it = inner.nth(j)
      if (!(await it.count())) break
      const innerName = await nameOf(it)
      if (!innerName || SKIP.test(innerName)) continue
      lastAction = `${label}: "${name}" → "${innerName}"`
      await it.click({ timeout: 1500 }).catch(() => {})
      clicked[label].push(`${name} → ${innerName}`)
      await settle(page)
      await page.keyboard.press('Escape')
      await settle(page)
      if (await page.locator('[role="menu"]:visible, [role="dialog"]:visible').count() === 0) break
    }
    await page.keyboard.press('Escape')
    await page.keyboard.press('Escape')
    await settle(page)
  }
}

test.beforeAll(async () => {
  data = await seed()
})

test.afterAll(async () => {
  writeFileSync(`${OUT}ui-crawl.json`, JSON.stringify({ when: new Date().toISOString(), clickedCount: Object.fromEntries(Object.entries(clicked).map(([k, v]) => [k, v.length])), clicked, issues }, null, 2))
})

test('넓은 화면(1440) — 모든 화면 크롤', async ({ page }) => {
  watch(page, 'desktop')
  await login(page, data.user.username, data.user.password)
  const routes: [string, string][] = [
    ['drive', '/drive'],
    ['drive-folder', `/drive/${data.folderId}`],
    ['team', `/teams/${data.teamId}/folders/${data.teamRootFolderId}`],
    ['trash', '/trash'],
    ['team-trash', `/teams/${data.teamId}/trash`],
    ['search', '/search?q=%ED%9A%8C%EC%9D%98'],
    ['editor', `/files/${data.textFileId}/edit`],
  ]
  for (const [label, path] of routes) await crawl(page, path, label)
})

test('좁은 화면(375·768·1024) — 넘침·스크린샷·메뉴', async ({ browser }) => {
  for (const [w, h] of [[375, 812], [768, 1024], [1024, 768]]) {
    const ctx = await browser.newContext({ viewport: { width: w, height: h }, locale: 'ko-KR', timezoneId: 'Asia/Seoul' })
    const page = await ctx.newPage()
    watch(page, `w${w}`)
    await page.goto('/login')
    await settle(page)
    const overflow = async (label: string) => {
      const o = await page.evaluate(() => ({ sw: document.documentElement.scrollWidth, cw: document.documentElement.clientWidth }))
      if (o.sw > o.cw + 1) issues.push({ kind: 'horizontal-overflow', page: `w${w} ${label}`, detail: `scrollWidth ${o.sw} > ${o.cw}` })
      await page.screenshot({ path: `${OUT}screens/w${w}-${label}.png`, fullPage: true })
    }
    await overflow('login')
    await login(page, data.user.username, data.user.password)
    for (const [label, path] of [['drive', '/drive'], ['folder', `/drive/${data.folderId}`], ['team', `/teams/${data.teamId}/folders/${data.teamRootFolderId}`], ['trash', '/trash'], ['search', '/search?q=%ED%9A%8C%EC%9D%98'], ['editor', `/files/${data.textFileId}/edit`]]) {
      await page.goto(path)
      await settle(page)
      await overflow(label)
    }
    if (w < 1024) {
      await page.goto('/drive')
      await settle(page)
      lastAction = `w${w}: 메뉴 열기`
      await page.getByRole('button', { name: '메뉴 열기' }).click()
      await settle(page)
      await page.screenshot({ path: `${OUT}screens/w${w}-menu.png` })
      await page.keyboard.press('Escape')
    }
    if (w < 1280) {
      await page.goto(`/teams/${data.teamId}/folders/${data.teamRootFolderId}`)
      await settle(page)
      const btn = page.getByRole('button', { name: /팀 패널|채팅|팀/ }).first()
      lastAction = `w${w}: 팀 패널 열기`
      await btn.click().catch(() => issues.push({ kind: 'missing', page: `w${w} team`, detail: '팀 패널 여는 버튼을 찾지 못함' }))
      await settle(page)
      await page.screenshot({ path: `${OUT}screens/w${w}-team-panel.png` })
    }
    await ctx.close()
  }
})

test('공유 받기(비로그인) 화면', async ({ browser }) => {
  const ctx = await browser.newContext()
  const page = await ctx.newPage()
  watch(page, 'share')
  await page.goto(`/share/${data.shareToken}`)
  await settle(page)
  await page.screenshot({ path: `${OUT}screens/share.png`, fullPage: true })
  lastAction = 'share: 틀린 비밀번호'
  const pw = page.getByLabel(/비밀번호/)
  await pw.fill('wrong-pass')
  await page.keyboard.press('Enter')
  await settle(page)
  lastAction = 'share: 맞는 비밀번호'
  await pw.fill(data.sharePassword)
  await page.keyboard.press('Enter')
  await settle(page)
  await page.screenshot({ path: `${OUT}screens/share-unlocked.png`, fullPage: true })
  await page.goto('/share/does-not-exist-token')
  await settle(page)
  await page.screenshot({ path: `${OUT}screens/share-missing.png`, fullPage: true })
  await ctx.close()
})

test('키보드 — 로그인 Tab 순서, 대화상자 초점 가둠·Esc 후 초점 복귀', async ({ browser }) => {
  const ctx = await browser.newContext()
  const page = await ctx.newPage()
  watch(page, 'keyboard')
  await page.goto('/login')
  await settle(page)
  const order: string[] = []
  for (let i = 0; i < 12; i++) {
    await page.keyboard.press('Tab')
    order.push(await page.evaluate(() => {
      const a = document.activeElement as HTMLElement | null
      if (!a) return '(없음)'
      const style = getComputedStyle(a)
      const visible = style.outlineStyle !== 'none' || style.boxShadow !== 'none'
      return `${a.tagName.toLowerCase()}[${a.getAttribute('aria-label') ?? a.getAttribute('name') ?? a.textContent?.trim().slice(0, 15)}]${visible ? '' : '(초점 표시 없음)'}`
    }))
  }
  issues.push({ kind: 'info.tab-order', page: 'login', detail: order.join(' → ') })
  await login(page, data.user.username, data.user.password)
  // 새 폴더 대화상자
  await page.getByRole('button', { name: '새 폴더' }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()
  let escaped = 0
  for (let i = 0; i < 15; i++) {
    await page.keyboard.press('Tab')
    // 네이티브 <dialog>(showModal)는 role 속성이 없으므로 dialog[open] 도 봅니다
    const where = await page.evaluate(() => {
      const a = document.activeElement
      if (a?.closest('dialog[open], [role="dialog"]')) return 'inside'
      return a === document.body || !a ? 'body' : `${a.tagName.toLowerCase()}:${a.textContent?.trim().slice(0, 20)}`
    })
    // body 는 브라우저 주소창 등 페이지 밖으로 잠시 나간 경우(네이티브 모달 동작) — 페이지 안의 다른 요소로 가면 문제
    if (where !== 'inside' && where !== 'body') escaped++
  }
  if (escaped) issues.push({ kind: 'focus-trap', page: 'drive 새 폴더', detail: `Tab 15번 중 ${escaped}번 대화상자 밖의 페이지 요소로 초점이 나감` })
  await page.keyboard.press('Escape')
  await settle(page)
  const back = await page.evaluate(() => (document.activeElement as HTMLElement | null)?.textContent?.trim() ?? '')
  if (!back.includes('새 폴더')) issues.push({ kind: 'focus-return', page: 'drive 새 폴더', detail: `Esc 뒤 초점: "${back.slice(0, 30)}"` })
  // 표: 키보드로 행 이동·열기
  await page.goto(`/drive/${data.folderId}`)
  await settle(page)
  await page.getByRole('checkbox', { name: /데이터-00\.csv 선택/ }).focus()
  await page.keyboard.press('Space')
  await settle(page)
  const selected = await page.getByRole('toolbar', { name: '선택한 항목 작업' }).count()
  if (!selected) issues.push({ kind: 'keyboard', page: 'drive 표', detail: '체크박스에 초점을 두고 Space 로 선택되지 않음' })
  await ctx.close()
})

test('주요 흐름 — 폼 입력과 결과', async ({ page }) => {
  watch(page, 'flow')
  await login(page, data.user.username, data.user.password)
  // 새 폴더
  lastAction = 'flow: 새 폴더 만들기'
  await page.getByRole('button', { name: '새 폴더' }).click()
  await page.getByRole('dialog').getByRole('textbox').fill('흐름 폴더')
  await page.getByRole('dialog').getByRole('button', { name: '만들기' }).click()
  await expect(page.getByRole('button', { name: '흐름 폴더', exact: true })).toBeVisible()
  // 업로드
  lastAction = 'flow: 업로드'
  await page.locator('input[type="file"]').first().setInputFiles({ name: '업로드.txt', mimeType: 'text/plain', buffer: Buffer.from('업로드 내용') })
  await expect(page.getByRole('button', { name: '업로드.txt', exact: true })).toBeVisible()
  // 검색
  lastAction = 'flow: 검색'
  await page.getByRole('searchbox').or(page.getByPlaceholder(/검색/)).first().fill('업로드')
  await page.keyboard.press('Enter')
  await expect(page).toHaveURL(/\/search/)
  await settle(page)
  // 편집기: 저장·요약·번역·이탈 확인
  lastAction = 'flow: 편집기'
  await page.goto(`/files/${data.textFileId}/edit`)
  await settle(page)
  const editor = page.getByRole('textbox', { name: '문서 내용' })
  await editor.fill('편집기에서 바꾼 내용입니다. 두 번째 문장입니다.')
  await page.keyboard.press('Control+s')
  await page.keyboard.press('Meta+s')
  await settle(page)
  await page.getByRole('button', { name: /핵심 문장|요약/ }).first().click().catch(() => issues.push({ kind: 'missing', page: 'editor', detail: '요약 버튼 없음' }))
  await settle(page)
  await page.getByRole('button', { name: /번역|EN/ }).first().click().catch(() => issues.push({ kind: 'missing', page: 'editor', detail: '번역 버튼 없음' }))
  await settle(page)
  await editor.fill('저장하지 않은 변경')
  // 앱 안의 이동은 앱의 확인 대화상자(useBlocker)로 묻습니다 — 취소하면 편집기에 남아야 함
  await page.getByRole('link', { name: /내 드라이브/ }).first().click().catch(() => {})
  await settle(page)
  await page.screenshot({ path: `${OUT}screens/flow-editor-confirm.png` })
  await page.keyboard.press('Escape')
  await settle(page)
  await page.screenshot({ path: `${OUT}screens/flow-editor-leave.png` })
  const stayed = /\/edit$/.test(new URL(page.url()).pathname)
  issues.push({ kind: 'info.unsaved-guard', page: 'editor', detail: `저장 안 한 변경이 있을 때 앱 안 이동을 취소하면 편집기에 남음: ${stayed}` })
  // 팀 채팅 보내기 — 편집기를 떠날 때 묻는 확인(beforeunload)은 수락
  lastAction = 'flow: 채팅'
  page.once('dialog', (d) => d.accept())
  await page.goto(`/teams/${data.teamId}/folders/${data.teamRootFolderId}`)
  await settle(page)
  await page.getByRole('textbox', { name: '메시지' }).fill('크롤러가 보낸 메시지')
  await page.keyboard.press('Enter')
  await expect(page.getByText('크롤러가 보낸 메시지').first()).toBeVisible()
  // XSS 시드가 글자로만 보이는지
  const alerts: string[] = []
  page.on('dialog', (d) => {
    alerts.push(d.message())
    void d.dismiss()
  })
  await page.reload()
  await settle(page)
  const scriptText = await page.getByText('<img src=x onerror=alert(1)>', { exact: false }).count()
  if (alerts.length) issues.push({ kind: 'xss', page: 'team', detail: `alert 실행: ${alerts.join(',')}` })
  issues.push({ kind: 'info.xss-text', page: 'team', detail: `스크립트 문자열이 글자로 보인 곳 ${scriptText}곳, alert ${alerts.length}번` })
  // 알림
  lastAction = 'flow: 알림'
  await page.getByRole('button', { name: /알림/ }).first().click()
  await settle(page)
  await page.screenshot({ path: `${OUT}screens/flow-notifications.png` })
  await page.keyboard.press('Escape')
})
