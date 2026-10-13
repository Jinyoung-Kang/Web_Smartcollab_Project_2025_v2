import AxeBuilder from '@axe-core/playwright'
import { randomUUID } from 'node:crypto'
import { expect, test, watchCsp } from './fixtures'

const ADMIN_USERNAME = process.env.ADMIN_USERNAME
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD

// [ADM-13] 관리 전용 계정(ADR-0015): 계정 목록을 보고 계정을 삭제하면, 그 계정은 바로 쓸 수 없게 된다
test('[ADM-13] 관리자는 계정 목록에서 계정을 삭제하고, 삭제된 계정은 로그인 상태가 끊긴다', async ({ browser, page }) => {
  test.skip(!ADMIN_USERNAME || !ADMIN_PASSWORD, 'ADMIN_USERNAME·ADMIN_PASSWORD 로 관리자 계정을 켠 스택에서만 실행합니다')

  // 삭제할 계정 — 새로 가입해 로그인해 둔 상태
  const username = `adm${randomUUID().replaceAll('-', '').slice(0, 10)}`
  const password = `pw-${randomUUID()}`
  const userContext = await browser.newContext()
  const userPage = await userContext.newPage()
  watchCsp(userPage)
  const csrf = await (await userContext.request.get('/api/auth/csrf')).json() as { headerName: string; token: string }
  const signup = await userContext.request.post('/api/auth/signup', {
    headers: { [csrf.headerName]: csrf.token },
    data: { username, password, passwordConfirm: password, name: '삭제될 사용자' },
  })
  expect(signup.status()).toBe(201)
  await userPage.goto('/drive')
  await expect(userPage.getByRole('heading', { level: 1, name: '내 드라이브' })).toBeVisible()

  // 관리자로 로그인하면 관리 화면으로 가고, 드라이브 주소로 와도 관리 화면으로 돌아간다
  await page.goto('/login')
  await page.getByLabel('아이디').fill(ADMIN_USERNAME!)
  await page.getByLabel('비밀번호', { exact: true }).fill(ADMIN_PASSWORD!)
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page).toHaveURL(/\/admin$/)
  await expect(page.getByRole('heading', { level: 1, name: '계정 관리' })).toBeVisible()
  await page.goto('/drive')
  await expect(page).toHaveURL(/\/admin$/)

  const row = page.getByRole('row').filter({ hasText: username })
  await expect(row).toContainText('삭제될 사용자')
  await expect(row).toContainText('없음')   // 소속 팀
  await expect(row).toContainText('0 B /')  // 드라이브 사용량
  // 접근성: WCAG 2.1 AA·모범 사례 + 보이는 글자를 포함하는 이름(실험 규칙)
  const axe = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'best-practice'])
    .analyze()
  expect(axe.violations.map((v) => `${v.id} (${v.impact}) ×${v.nodes.length}: ${v.nodes[0]?.target.join(' ')}`), '계정 관리').toEqual([])
  const labelInName = await new AxeBuilder({ page }).withRules(['label-content-name-mismatch']).analyze()
  expect(labelInName.violations.map((v) => v.id), '보이는 글자와 접근 가능한 이름').toEqual([])
  // 좁은 화면: 표는 표 안에서만 가로로 스크롤되고 문서는 넓어지지 않는다(머리글의 sr-only 글자가 문서를 넓히던 문제)
  await page.setViewportSize({ width: 375, height: 812 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth), '375 폭 가로 넘침').toBe(0)
  await page.setViewportSize({ width: 1280, height: 720 })

  // 아이디를 입력해야 삭제된다
  await row.getByRole('button', { name: `${username} 계정 삭제` }).click()
  const dialog = page.getByRole('dialog', { name: `${username} 계정 삭제` })
  await expect(dialog.getByRole('button', { name: '계정 삭제' })).toBeDisabled()
  await dialog.getByRole('textbox').fill(username)
  await dialog.getByRole('button', { name: '계정 삭제' }).click()
  await expect(page.getByText(`${username} 계정을 삭제했습니다.`)).toBeVisible()
  await expect(page.getByRole('row').filter({ hasText: username })).toHaveCount(0)

  // 삭제된 계정의 열린 화면은 새로고침하면 로그인 화면으로 가고, 다시 로그인할 수 없다
  await userPage.reload()
  await expect(userPage).toHaveURL(/\/login/)
  const fresh = await (await userContext.request.get('/api/auth/csrf')).json() as { headerName: string; token: string }
  const relogin = await userContext.request.post('/api/auth/login', {
    headers: { [fresh.headerName]: fresh.token },
    data: { username, password },
  })
  expect(relogin.status()).toBe(401)
  await userContext.close()
})
