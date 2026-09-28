import { expect, test, watchCsp } from './fixtures'
import { DEMO_PASSWORD, login, newUserPage, openDemoTeam } from './helpers'

test('보안 헤더: CSP 로 외부 스크립트·eval 을 막는다', async ({ request }) => {
  const res = await request.get('/login')
  expect(res.status()).toBe(200)
  const csp = res.headers()['content-security-policy']
  expect(csp).toContain("script-src 'self'")
  expect(csp).not.toContain('unsafe-eval')
  expect(csp).not.toContain("'unsafe-inline'")
  expect((await request.get('/api/auth/me')).status()).toBe(401)
})

test('팀 문서를 열어 편집·저장하고 버전 기록에서 확인한다', async ({ page }) => {
  await login(page, 'demo1')
  await openDemoTeam(page)
  await page.getByRole('button', { name: '회의록', exact: true }).click()
  await page.getByRole('button', { name: '주간 회의 메모.txt', exact: true }).click()
  await expect(page.getByRole('dialog')).toContainText('주간 회의 메모')
  await page.getByRole('button', { name: '편집기로 열기' }).click()

  const editor = page.getByLabel('문서 내용')
  await editor.click()
  await editor.press('ControlOrMeta+End')
  const line = `E2E 편집 ${Date.now()}`
  await editor.pressSequentially(`\n${line}`)
  await page.getByRole('button', { name: '저장', exact: true }).click()
  await expect(page.getByText('새 버전으로 저장했습니다.')).toBeVisible()

  await page.getByRole('button', { name: '버전', exact: true }).click()
  const history = page.getByRole('dialog', { name: '버전 기록' })
  // 하단의 '현재 버전에 서명' 버튼도 부분 일치하므로 뱃지만 정확히 찾습니다 (버전 목록이 늦게 오면 버튼을 잡고 통과하던 불안정한 검증)
  await expect(history.getByText('현재 버전', { exact: true })).toBeVisible()
  await expect(history.getByText('김하늘').first()).toBeVisible()
})

test('파일을 올리고 이름을 바꾼 뒤 휴지통으로 보냈다가 복원한다', async ({ page }) => {
  await login(page, 'demo1')
  const name = `e2e-${Date.now()}.txt`
  await page.locator('input[type=file][multiple]').setInputFiles({ name, mimeType: 'text/plain', buffer: Buffer.from('hello e2e') })
  await expect(page.getByRole('region', { name: '업로드 진행 상황' })).toContainText('업로드 완료')
  await expect(page.getByRole('button', { name, exact: true })).toBeVisible()

  await page.getByLabel(`${name} 선택`).check()
  await page.getByRole('button', { name: '이름 바꾸기' }).click()
  const renamed = name.replace('.txt', '-renamed.txt')
  await page.getByRole('dialog').getByRole('textbox').fill(renamed)
  await page.getByRole('dialog').getByRole('button', { name: '바꾸기' }).click()
  await expect(page.getByRole('button', { name: renamed, exact: true })).toBeVisible()

  await page.getByLabel(`${renamed} 선택`).check()
  await page.getByRole('toolbar').getByRole('button', { name: '삭제' }).click()
  await page.getByRole('dialog').getByRole('button', { name: '삭제' }).click()
  await expect(page.getByRole('button', { name: renamed, exact: true })).toBeHidden()

  await page.getByRole('link', { name: '휴지통' }).click()
  const row = page.getByRole('listitem').filter({ hasText: renamed })
  await row.getByRole('button', { name: '복원' }).click()
  await expect(page.getByText(`'${renamed}'을(를) 원래 폴더로 복원했습니다.`)).toBeVisible()
})

test('[UX-06] 폴더를 지우면 안의 파일과 함께 휴지통으로 가고, 복원하면 돌아온다', async ({ page }) => {
  await login(page, 'demo1')
  const folder = `e2e-폴더-${Date.now()}`
  await page.getByRole('button', { name: '새 폴더' }).click()
  await page.getByRole('dialog').getByRole('textbox').fill(folder)
  await page.getByRole('dialog').getByRole('button', { name: '만들기' }).click()
  await page.getByRole('button', { name: folder, exact: true }).click()
  // 폴더 화면으로 바뀐 뒤에 올려야 그 폴더에 들어갑니다 (바뀌기 전이면 보이던 화면의 폴더에 올라감)
  await expect(page.getByRole('heading', { level: 1, name: folder })).toBeVisible()
  await page.locator('input[type=file][multiple]').setInputFiles({ name: 'inside.txt', mimeType: 'text/plain', buffer: Buffer.from('x') })
  await expect(page.getByRole('region', { name: '업로드 진행 상황' })).toContainText('업로드 완료')
  await page.getByRole('navigation', { name: '주 메뉴' }).getByRole('link', { name: '내 드라이브' }).click()

  await page.getByLabel(`${folder} 선택`).check()
  await page.getByRole('toolbar').getByRole('button', { name: '삭제' }).click()
  await expect(page.getByRole('dialog')).toContainText('휴지통으로 옮겨지며')
  await page.getByRole('dialog').getByRole('button', { name: '삭제' }).click()
  await expect(page.getByRole('button', { name: folder, exact: true })).toBeHidden()

  await page.getByRole('link', { name: '휴지통' }).click()
  const row = page.getByRole('listitem').filter({ hasText: folder })
  await expect(row).toContainText('폴더 · 파일 1개')
  await row.getByRole('button', { name: '복원' }).click()
  await expect(page.getByText(`원래 폴더로 복원했습니다.`)).toBeVisible()
  await page.getByRole('navigation', { name: '주 메뉴' }).getByRole('link', { name: '내 드라이브' }).click()
  await page.getByRole('button', { name: folder, exact: true }).click()
  await expect(page.getByRole('heading', { level: 1, name: folder })).toBeVisible()
  await expect(page.getByRole('button', { name: 'inside.txt', exact: true })).toBeVisible()
})

test('두 사용자가 실시간으로 채팅하고, 폴더 변경이 새로고침 없이 반영된다', async ({ browser, page }) => {
  await login(page, 'demo1')
  await openDemoTeam(page)
  const other = await newUserPage(browser, 'demo2')
  await openDemoTeam(other)

  const text = `안녕하세요 ${Date.now()}`
  await other.getByLabel('메시지').fill(text)
  await other.getByLabel('메시지').press('Enter')
  await expect(page.getByText(text)).toBeVisible({ timeout: 5000 })

  const folder = `실시간-${Date.now()}`
  await other.getByRole('button', { name: '새 폴더' }).click()
  await other.getByRole('dialog').getByRole('textbox').fill(folder)
  await other.getByRole('dialog').getByRole('button', { name: '만들기' }).click()
  await expect(page.getByRole('button', { name: folder, exact: true })).toBeVisible({ timeout: 5000 })
  await other.context().close()
})

test('팀 채팅에 올린 파일을 누르면 드라이브와 같은 미리보기가 열린다', async ({ page }) => {
  await login(page, 'demo1')
  await openDemoTeam(page)
  const name = `채팅-미리보기-${Date.now()}.png`
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=', 'base64') // 1×1
  await page.locator('form', { has: page.getByLabel('메시지') }).locator('input[type=file]')
    .setInputFiles({ name, mimeType: 'image/png', buffer: png })
  await page.getByRole('button', { name: `${name} 미리보기` }).click()

  const dialog = page.getByRole('dialog', { name })
  const image = dialog.getByRole('img', { name })
  await expect(image).toBeVisible()
  await expect.poll(() => image.evaluate((el: HTMLImageElement) => el.naturalWidth)).toBeGreaterThan(0)
  await expect(dialog.getByRole('link', { name: '내려받기' })).toHaveAttribute('href', /\/api\/files\/\d+\/download$/)
})

test('공유 링크로 로그인 없이 내려받는다', async ({ page, browser }) => {
  await login(page, 'demo1')
  await openDemoTeam(page)
  await page.getByRole('button', { name: '기획', exact: true }).click()
  await page.getByLabel('프로젝트 개요.md 선택').check()
  await page.getByRole('toolbar').getByRole('button', { name: '공유' }).click()
  await page.context().grantPermissions(['clipboard-read', 'clipboard-write'])
  await page.getByRole('button', { name: /링크 만들고 복사/ }).click()
  const path = await page.getByRole('dialog').locator('p.font-mono').first().textContent()
  expect(path).toContain('/share/')

  const anonymous = await (await browser.newContext()).newPage()
  watchCsp(anonymous)
  await anonymous.goto(path!.trim())
  await expect(anonymous.getByText('프로젝트 개요.md')).toBeVisible()
  const download = anonymous.waitForEvent('download')
  await anonymous.getByRole('button', { name: '내려받기' }).click()
  expect((await download).suggestedFilename()).toBe('프로젝트 개요.md')
})

test('[BUG-02] 검색 화면은 주소로 직접 열거나 새로고침해도 열린다', async ({ page }) => {
  await login(page, 'demo1')
  await page.goto('/search?q=' + encodeURIComponent('할 일'))
  await expect(page.getByRole('heading', { name: '‘할 일’ 검색 결과' })).toBeVisible()
  await expect(page.getByText('할 일.txt')).toBeVisible()
  await page.reload()
  await expect(page.getByRole('heading', { name: '‘할 일’ 검색 결과' })).toBeVisible()
})

test('[BUG-09] 로그아웃하면 로그인 화면이 보이고, 로그인이 필요한 화면으로 되돌아가지 않는다', async ({ page }) => {
  await login(page, 'demo1')
  await page.getByRole('button', { name: '내 계정' }).click()
  await page.getByRole('menuitem', { name: '로그아웃' }).click()
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByLabel('아이디')).toBeVisible()
  await page.goto('/drive')
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByLabel('아이디')).toBeVisible()
})

test('[BUG-09] 비밀번호를 한 번 틀려도 올바른 비밀번호로 로그인된다', async ({ page }) => {
  await page.goto('/login')
  await page.getByLabel('아이디').fill('demo1')
  await page.getByLabel('비밀번호', { exact: true }).fill('wrong-pass-1')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('일치하지 않습니다')
  await page.getByLabel('비밀번호', { exact: true }).fill(DEMO_PASSWORD)
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page).toHaveURL(/\/drive/)
  await expect(page.getByRole('button', { name: '내 계정' })).toBeVisible()
})
