// 접근성 확장 점검 — 기존 a11y E2E 가 덮지 않은 화면·상태·대화상자·좁은 화면을 axe(WCAG 2.0/2.1 A·AA + 모범 사례)로 검사합니다.
// 위반이 있어도 멈추지 않고 모두 모아 qa/results/a11y-extended.json 에 남깁니다.
import AxeBuilder from '@axe-core/playwright'
import { test, expect, type Page } from '@playwright/test'
import { writeFileSync } from 'node:fs'
import { seed, type Seed } from './seed'

const TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'best-practice']
const results: { screen: string; violations: { id: string; impact: string | null | undefined; nodes: number; target: string; help: string; wcag: string[] }[] }[] = []
let data: Seed

async function scan(page: Page, screen: string) {
  await page.waitForLoadState('networkidle', { timeout: 5000 }).catch(() => {})
  await page.waitForFunction(() => document.getAnimations().every((a) => a.playState !== 'running'))
  const r = await new AxeBuilder({ page }).withTags(TAGS).analyze()
  results.push({
    screen,
    violations: r.violations.map((v) => ({ id: v.id, impact: v.impact, nodes: v.nodes.length, target: v.nodes[0]?.target.join(' ') ?? '', help: v.help, wcag: v.tags.filter((t) => /^wcag\d/.test(t)) })),
  })
}

async function login(page: Page) {
  await page.goto('/login')
  await page.getByLabel('아이디').fill(data.user.username)
  await page.getByLabel('비밀번호', { exact: true }).fill(data.user.password)
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page).toHaveURL(/\/drive/)
}

async function rowMenu(page: Page, name: string, action: string) {
  await page.getByRole('button', { name: `${name} 작업 메뉴` }).click()
  await page.getByRole('menuitem', { name: action }).click()
}

test.beforeAll(async () => {
  data = await seed()
})

test.afterAll(() => {
  const total = results.reduce((n, r) => n + r.violations.length, 0)
  writeFileSync(new URL('../../qa/results/a11y-extended.json', import.meta.url), JSON.stringify({ when: new Date().toISOString(), screens: results.length, totalViolationTypes: total, results }, null, 2))
})

test('비로그인 화면 — 가입·오류 상태·공유 받기', async ({ page }) => {
  await page.goto('/signup')
  await scan(page, '가입')
  await page.getByRole('button', { name: '가입하기' }).or(page.getByRole('button', { name: '회원가입', exact: true })).last().click()
  await scan(page, '가입 — 빈 값으로 제출한 오류 상태')
  await page.goto('/login')
  await page.getByLabel('아이디').fill(data.user.username)
  await page.getByLabel('비밀번호', { exact: true }).fill('wrong-password-1')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await page.waitForTimeout(500)
  await scan(page, '로그인 — 비밀번호 오류 상태')
  await page.goto(`/share/${data.shareToken}`)
  await scan(page, '공유 받기 — 비밀번호 입력')
  await page.getByLabel(/비밀번호/).fill('wrong-pw')
  await page.keyboard.press('Enter')
  await page.waitForTimeout(500)
  await scan(page, '공유 받기 — 비밀번호 오류')
  await page.getByLabel(/비밀번호/).fill(data.sharePassword)
  await page.keyboard.press('Enter')
  await page.waitForTimeout(500)
  await scan(page, '공유 받기 — 잠금 해제')
  await page.goto('/share/no-such-token-qa')
  await scan(page, '공유 받기 — 없는 링크')
})

test('로그인 화면 — 대화상자·편집기·검색·빈/오류 상태', async ({ page }) => {
  await login(page)
  await page.getByRole('button', { name: '새 폴더' }).click()
  await scan(page, '새 폴더 대화상자')
  await page.keyboard.press('Escape')
  await rowMenu(page, '회의록.txt', '이름 바꾸기')
  await scan(page, '이름 바꾸기 대화상자')
  await page.keyboard.press('Escape')
  await rowMenu(page, '회의록.txt', '이동')
  await page.waitForTimeout(500)
  await scan(page, '이동 대화상자')
  await page.keyboard.press('Escape')
  await rowMenu(page, '회의록.txt', '복사')
  await page.waitForTimeout(500)
  await scan(page, '복사 대화상자')
  await page.keyboard.press('Escape')
  await rowMenu(page, '회의록.txt', '공유 링크')
  await page.waitForTimeout(500)
  await scan(page, '공유 링크 대화상자(링크 있음)')
  await page.keyboard.press('Escape')
  await rowMenu(page, '스케치.png', '미리보기')
  await page.waitForTimeout(800)
  await scan(page, '미리보기 대화상자(이미지)')
  await page.keyboard.press('Escape')
  await rowMenu(page, '회의록.txt', '미리보기')
  await page.waitForTimeout(800)
  await scan(page, '미리보기 대화상자(텍스트)')
  await page.keyboard.press('Escape')
  await rowMenu(page, '회의록.txt', '삭제')
  await page.waitForTimeout(300)
  await scan(page, '삭제 확인 대화상자')
  await page.keyboard.press('Escape')
  await page.getByRole('button', { name: '내 계정' }).click()
  await scan(page, '내 계정 메뉴')
  await page.getByRole('menuitem', { name: '회원 탈퇴' }).or(page.getByRole('button', { name: '회원 탈퇴' })).first().click()
  await scan(page, '회원 탈퇴 대화상자')
  await page.keyboard.press('Escape')
  await page.getByRole('button', { name: '새 팀 만들기' }).click()
  await scan(page, '새 팀 만들기 대화상자')
  await page.keyboard.press('Escape')
  await page.locator('input[type="file"]').first().setInputFiles({ name: 'a11y-upload.txt', mimeType: 'text/plain', buffer: Buffer.from('x') })
  await page.getByRole('region', { name: '업로드 진행 상황' }).waitFor()
  await scan(page, '업로드 진행 상황 패널')
  await page.goto('/search?q=%ED%9A%8C%EC%9D%98')
  await scan(page, '검색 결과')
  await page.goto('/search?q=zzzz-no-match')
  await scan(page, '검색 결과 없음')
  await page.goto('/drive/999999999')
  await scan(page, '없는 폴더')
  await page.goto(`/files/${data.textFileId}/edit`)
  await scan(page, '편집기')
  await page.getByRole('button', { name: /핵심 문장|요약/ }).first().click()
  await page.waitForTimeout(800)
  await scan(page, '편집기 — 핵심 문장 패널')
  await page.getByRole('button', { name: /번역|EN/ }).first().click().catch(() => {})
  await page.waitForTimeout(800)
  await scan(page, '편집기 — 번역 결과')
  await page.goto('/trash')
  await scan(page, '휴지통(파일·폴더)')
})

test('팀 화면 — 멤버 탭·관리 메뉴·초대·권한 대화상자', async ({ page }) => {
  await login(page)
  await page.goto(`/teams/${data.teamId}/folders/${data.teamRootFolderId}`)
  await page.getByRole('tab', { name: /멤버/ }).click()
  await scan(page, '팀 멤버 탭')
  await page.getByRole('button', { name: /멤버 초대/ }).click()
  await scan(page, '멤버 초대 대화상자')
  await page.keyboard.press('Escape')
  await page.getByRole('button', { name: /관리$/ }).first().click()
  await scan(page, '멤버 관리 메뉴')
  await page.getByRole('menuitem', { name: /권한/ }).first().click().catch(() => {})
  await page.waitForTimeout(300)
  await scan(page, '권한 변경 대화상자')
  await page.keyboard.press('Escape')
  await page.getByRole('button', { name: '팀 메뉴' }).click()
  await scan(page, '팀 메뉴')
  await page.keyboard.press('Escape')
  await page.goto(`/teams/${data.teamId}/trash`)
  await scan(page, '팀 휴지통')
})

test('좁은 화면(375) — 드라이브·메뉴·팀 패널·편집기', async ({ browser }) => {
  const ctx = await browser.newContext({ viewport: { width: 375, height: 812 }, locale: 'ko-KR' })
  const page = await ctx.newPage()
  await login(page)
  await scan(page, '375 내 드라이브')
  await page.getByRole('button', { name: '메뉴 열기' }).click()
  await page.waitForTimeout(400)
  await scan(page, '375 주 메뉴 열림')
  await page.keyboard.press('Escape')
  await page.goto(`/teams/${data.teamId}/folders/${data.teamRootFolderId}`)
  await page.getByRole('button', { name: /팀 패널|채팅|팀/ }).first().click().catch(() => {})
  await page.waitForTimeout(500)
  await scan(page, '375 팀 패널 열림')
  await page.goto(`/files/${data.textFileId}/edit`)
  await scan(page, '375 편집기')
  await ctx.close()
})
