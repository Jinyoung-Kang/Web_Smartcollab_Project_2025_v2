import AxeBuilder from '@axe-core/playwright'
import type { Page } from '@playwright/test'
import { expect, test } from './fixtures'
import { login, openDemoTeam } from './helpers'

/** WCAG 2.1 A·AA 와 axe 의 모범 사례 규칙 */
const RULE_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'best-practice']

async function violations(page: Page) {
  // 나타나는 중인 요소(투명도 애니메이션)는 대비가 낮게 측정되므로 애니메이션이 끝난 뒤 검사합니다.
  await page.waitForFunction(() => document.getAnimations().every((a) => a.playState !== 'running'))
  const result = await new AxeBuilder({ page }).withTags(RULE_TAGS).analyze()
  return result.violations.map((v) => `${v.id} (${v.impact}) ×${v.nodes.length}: ${v.nodes[0]?.target.join(' ')}`)
}

// 수정 전 axe 결과: critical 1(알림 팝업 ARIA 구조), serious 명도 대비 최대 19곳/화면, main·h1 없음, 랜드마크 이름 중복, 빈 표 머리글
test('[UX-01] 주요 화면에 접근성 위반(WCAG 2.1 AA·모범 사례)이 없다', async ({ page }) => {
  await page.goto('/login')
  await page.getByLabel('아이디').waitFor()
  expect(await violations(page), '로그인').toEqual([])

  await login(page, 'demo1')
  expect(await violations(page), '내 드라이브').toEqual([])

  await openDemoTeam(page)
  // 내가 보낸 파일 카드(옅은 색 배경)도 검사하도록 채팅에 파일을 하나 올립니다 — 없으면 이 경우를 놓칩니다.
  const shared = `a11y-${Date.now()}.txt`
  await page.locator('form', { has: page.getByLabel('메시지') }).locator('input[type=file]')
    .setInputFiles({ name: shared, mimeType: 'text/plain', buffer: Buffer.from('a11y') })
  await expect(page.getByRole('button', { name: `${shared} 미리보기` })).toBeVisible()
  await page.getByRole('button', { name: '회의록', exact: true }).click()
  await page.getByLabel('킥오프 회의.md 선택').check()
  expect(await violations(page), '팀 드라이브·채팅·선택 작업 바').toEqual([])

  await page.getByRole('button', { name: '버전 기록' }).first().click()
  await expect(page.getByRole('dialog', { name: '버전 기록' })).toBeVisible()
  expect(await violations(page), '버전 기록').toEqual([])
  await page.keyboard.press('Escape')

  await page.getByRole('button', { name: /^알림/ }).click()
  await expect(page.getByRole('dialog', { name: '알림' })).toBeVisible()
  expect(await violations(page), '알림').toEqual([])
  await page.keyboard.press('Escape')

  await page.goto('/trash')
  await page.getByRole('heading', { level: 1, name: '휴지통' }).waitFor()
  expect(await violations(page), '휴지통').toEqual([])
})

test('[UX-02] 화면마다 탭 제목이 바뀐다', async ({ page }) => {
  await page.goto('/login')
  await expect(page).toHaveTitle('로그인 · SmartCollab')
  await login(page, 'demo1')
  await expect(page).toHaveTitle('내 드라이브 · SmartCollab')
  await page.goto('/trash')
  await expect(page).toHaveTitle('휴지통 · SmartCollab')
})
