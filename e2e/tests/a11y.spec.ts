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

// 출시 기준 QA 의 확장 점검(qa/results/a11y-extended.json)에서 찾은 위반: 팀 메뉴 안의 링크가 menuitem 이 아님(critical),
// 접속 표시 점의 aria-label(역할 없는 span), 좁은 화면에서 이름 없는 로고 링크, 공유 받기 화면의 랜드마크 밖 내용, 없는 폴더 화면의 h1,
// 그리고 Lighthouse 가 잡은 "보이는 글자를 포함하지 않는 이름"(WCAG 2.5.3, axe 에서는 기본으로 꺼진 실험 규칙이라 따로 켭니다)
test('[QA-09~13] 팀 멤버·팀 메뉴·없는 폴더·공유 받기·좁은 화면에도 접근성 위반이 없다', async ({ page }) => {
  await login(page, 'demo1')
  const labelInName = await new AxeBuilder({ page }).withRules(['label-content-name-mismatch']).analyze()
  expect(labelInName.violations.map((v) => `${v.id}: ${v.nodes[0]?.target.join(' ')}`), '보이는 글자와 접근 가능한 이름').toEqual([])
  await openDemoTeam(page)
  await page.getByRole('tab', { name: /멤버/ }).click()
  expect(await violations(page), '팀 멤버 목록').toEqual([])

  await page.getByRole('button', { name: '팀 메뉴' }).click()
  await expect(page.getByRole('menu')).toBeVisible()
  expect(await violations(page), '팀 메뉴').toEqual([])
  await page.keyboard.press('Escape')

  await page.goto('/drive/999999999')
  await page.getByText('폴더를 찾을 수 없습니다').waitFor()
  expect(await violations(page), '없는 폴더').toEqual([])

  await page.goto('/share/no-such-share-token')
  await page.getByText(/링크/).first().waitFor()
  expect(await violations(page), '공유 받기(없는 링크)').toEqual([])

  await page.setViewportSize({ width: 375, height: 812 })
  await page.goto('/drive')
  await page.getByRole('button', { name: '메뉴 열기' }).waitFor()
  expect(await violations(page), '좁은 화면 내 드라이브').toEqual([])
})

test('[UX-02] 화면마다 탭 제목이 바뀐다', async ({ page }) => {
  await page.goto('/login')
  await expect(page).toHaveTitle('로그인 · SmartCollab')
  await login(page, 'demo1')
  await expect(page).toHaveTitle('내 드라이브 · SmartCollab')
  await page.goto('/trash')
  await expect(page).toHaveTitle('휴지통 · SmartCollab')
})
