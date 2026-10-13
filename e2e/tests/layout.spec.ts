import { randomUUID } from 'node:crypto'
import type { APIRequestContext, Browser, Locator } from '@playwright/test'
import { expect, test, watchCsp } from './fixtures'
import { login } from './helpers'

// [DEP-04] 운영 도메인처럼 주소가 길면 공유 대화상자의 "만든 링크" 줄이 대화상자 밖으로 밀려 잘렸습니다(복사·해제 단추가 가려짐).
// 로컬(localhost)은 주소가 짧아 드러나지 않아, Chrome 이 내 컴퓨터로 보내는 긴 *.localhost 이름으로 접속해 운영과 같은 길이를 만듭니다.
test('[DEP-04] 공유 대화상자의 만든 링크는 주소가 길어도 대화상자 안에 들어간다', async ({ browser }) => {
  const base = new URL(process.env.E2E_BASE_URL ?? 'http://localhost:8080')
  test.skip(base.hostname !== 'localhost', 'localhost 로 띄운 스택에서만 긴 하위 도메인을 만들 수 있습니다')
  base.hostname = 'share-dialog-long-origin-check.localhost'
  const context = await browser.newContext({ baseURL: base.origin, viewport: { width: 1200, height: 896 } })
  const page = await context.newPage()
  watchCsp(page)

  await login(page, 'demo1')
  const name = `share-layout-${Date.now()}.txt`
  await page.locator('input[type=file][multiple]').setInputFiles({ name, mimeType: 'text/plain', buffer: Buffer.from('layout') })
  await page.getByLabel(`${name} 선택`).check()
  await page.getByRole('toolbar').getByRole('button', { name: '공유' }).click()
  const dialog = page.getByRole('dialog', { name: '공유 링크' })
  await dialog.getByRole('button', { name: '링크 만들고 복사' }).click()
  const item = dialog.getByRole('listitem').first()
  await expect(item).toBeVisible()

  const box = (await dialog.boundingBox())!
  const right = box.x + box.width
  for (const target of [item, item.getByRole('button', { name: '링크 복사' }), item.getByRole('button', { name: '링크 해제' })]) {
    const b = (await target.boundingBox())!
    expect(b.x + b.width, '대화상자 오른쪽 끝을 넘지 않음').toBeLessThanOrEqual(right)
  }
  await context.close()
})

/** API 로 가입한 새 사용자(로그인된 브라우저 문맥) */
async function signUp(browser: Browser, name: string) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const page = await context.newPage()
  watchCsp(page)
  const username = `ux${randomUUID().replaceAll('-', '').slice(0, 10)}`
  const password = `pw1-${randomUUID()}`
  const res = await send(context.request, 'POST', '/api/auth/signup', { username, password, passwordConfirm: password, name })
  expect(res.status()).toBe(201)
  return { context, page, username }
}

async function send(request: APIRequestContext, method: string, url: string, data?: unknown) {
  const csrf = await (await request.get('/api/auth/csrf')).json() as { headerName: string; token: string }
  return request.fetch(url, { method, headers: { [csrf.headerName]: csrf.token }, data })
}

/** target 의 오른쪽 끝이 area 의 오른쪽 끝을 넘지 않음 (배치 검사는 모두 확인하고 한꺼번에 알림) */
async function expectInside(target: Locator, area: Locator, what: string) {
  const t = (await target.boundingBox())!
  const a = (await area.boundingBox())!
  expect.soft(t.x + t.width, what).toBeLessThanOrEqual(a.x + a.width + 1)
}

// [UX-07] 아이디는 20자까지지만 이름(50자)·팀 이름(100자)은 길 수 있고, 띄어쓰기가 없으면 줄이 바뀌지 않습니다. 그런 이름이
// 사이드바·헤더·팀 멤버 목록·채팅·대화상자 밖으로 밀려 나가 메뉴·단추가 가려지고 로고가 눌렸습니다. 공유 대화상자(DEP-04)와 같은 원인
// (grid 열 너비 하한 auto, 줄지 않는 flex 항목)입니다.
test('[UX-07] 긴 사용자 이름·팀 이름이 사이드바·헤더·멤버 목록·채팅·대화상자 밖으로 넘치지 않는다', async ({ browser }) => {
  const leader = await signUp(browser, '가'.repeat(50))
  const member = await signUp(browser, '나'.repeat(50))
  const team = await (await send(leader.context.request, 'POST', '/api/teams', { name: 'W'.repeat(100) })).json() as { id: number }
  expect((await send(leader.context.request, 'POST', `/api/teams/${team.id}/invitations`, { username: member.username })).status()).toBe(201)
  const notes = await (await member.context.request.get('/api/notifications')).json() as { items: { invitationId: number }[] }
  expect((await send(member.context.request, 'POST', `/api/invitations/${notes.items[0].invitationId}/accept`)).status()).toBe(204)
  expect((await send(member.context.request, 'POST', `/api/teams/${team.id}/messages`, { content: '긴 이름 확인' })).status()).toBe(201)
  const page = leader.page

  await page.goto('/drive')
  await expect(page.getByRole('heading', { level: 1, name: '내 드라이브' })).toBeVisible()
  // 사이드바: 팀 링크와 인원 수가 사이드바 안에 있고 사이드바가 가로로 스크롤되지 않음
  const nav = page.getByRole('navigation', { name: '주 메뉴' })
  const teamLink = nav.getByRole('link', { name: /W{20}/ })
  await expectInside(teamLink, nav, '사이드바 팀 링크')
  await expectInside(teamLink.getByText('2명'), nav, '사이드바 팀 인원 수')
  expect.soft(await nav.evaluate((el) => el.scrollWidth - el.clientWidth), '사이드바 가로 넘침').toBe(0)
  // 헤더: 로고가 눌리지 않고 계정 단추가 헤더 안에 있음, 계정 메뉴 안의 이름도 메뉴 안에
  const header = page.locator('header')
  const logo = header.getByRole('link', { name: 'SmartCollab' })
  expect.soft(await logo.evaluate((el) => el.scrollWidth - el.clientWidth), '로고 글자 잘림').toBe(0)
  const account = header.getByRole('button', { name: /내 계정/ })
  await expectInside(account, header, '헤더 계정 단추')
  expect.soft((await header.getByRole('textbox', { name: '파일 검색' }).boundingBox())!.width, '검색 칸 너비').toBeGreaterThanOrEqual(240)
  await account.click()
  const accountMenu = page.getByRole('menu')
  await expectInside(accountMenu.getByText('가'.repeat(50)), accountMenu, '계정 메뉴의 이름')
  await page.keyboard.press('Escape')

  // 팀 화면: 채팅의 보낸 사람 이름, 멤버 목록의 이름과 관리 단추가 팀 패널 안에
  await page.goto(`/teams/${team.id}`)
  await expect(page).toHaveURL(/\/folders\//)
  const tabs = page.getByRole('tablist')
  const panel = tabs.locator('xpath=..')
  await expectInside(panel.getByText('나'.repeat(50)), panel, '채팅 보낸 사람 이름')
  await tabs.getByRole('tab', { name: /멤버/ }).click()
  const rows = panel.getByRole('listitem')
  await expect(rows).toHaveCount(2)
  for (const row of await rows.all()) await expectInside(row, panel, '멤버 줄')
  const manage = panel.getByRole('button', { name: /관리$/ })
  await expectInside(manage, panel, '멤버 관리 단추')
  // 권한 변경 대화상자: 설명 줄의 이름이 대화상자 안에서 줄바꿈됨
  await manage.click()
  await page.getByRole('menuitem', { name: '권한 변경' }).click()
  const dialog = page.getByRole('dialog', { name: '권한 변경' })
  expect.soft(await dialog.evaluate((el) => el.scrollWidth - el.clientWidth), '권한 변경 대화상자 가로 넘침').toBe(0)
  await page.keyboard.press('Escape')

  // 좁은 화면: 로고 아이콘이 눌리지 않음
  await page.setViewportSize({ width: 375, height: 812 })
  await page.goto('/drive')
  await expect(page.getByRole('heading', { level: 1, name: '내 드라이브' })).toBeVisible()
  expect.soft((await logo.locator('img').boundingBox())!.width, '좁은 화면의 로고 아이콘').toBeGreaterThanOrEqual(27)
  expect.soft(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth), '375 폭 가로 넘침').toBe(0)

  await leader.context.close()
  await member.context.close()
})
