import { expect, test } from '@playwright/test'
import { login, newUserPage, openDemoTeam } from './helpers'

test('보안 헤더: CSP 로 외부 스크립트·eval 을 막는다', async ({ request }) => {
  const res = await request.get('/login')
  expect(res.status()).toBe(200)
  const csp = res.headers()['content-security-policy']
  expect(csp).toContain("script-src 'self'")
  expect(csp).not.toContain('unsafe-eval')
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
  await expect(history.getByText('현재 버전')).toBeVisible()
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
  await anonymous.goto(path!.trim())
  await expect(anonymous.getByText('프로젝트 개요.md')).toBeVisible()
  const download = anonymous.waitForEvent('download')
  await anonymous.getByRole('button', { name: '내려받기' }).click()
  expect((await download).suggestedFilename()).toBe('프로젝트 개요.md')
})
