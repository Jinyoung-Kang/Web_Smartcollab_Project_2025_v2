// 내 변경 직후 같은 목록을 몇 번 다시 불러오는지 — 변경 요청의 무효화 + 실시간(웹소켓) 이벤트가 겹치는지 확인
import { test, expect, type Page } from '@playwright/test'
import { writeFileSync } from 'node:fs'
import { seed, type Seed } from './seed'

let data: Seed
test.beforeAll(async () => {
  data = await seed()
})

function counter(page: Page) {
  const log: { t: number; key: string }[] = []
  page.on('request', (r) => {
    const u = new URL(r.url())
    if (u.pathname.startsWith('/api/') && r.method() === 'GET') log.push({ t: Date.now(), key: u.pathname + u.search })
  })
  return {
    async after(label: string, action: () => Promise<void>) {
      const start = Date.now()
      await action()
      await page.waitForTimeout(2500)
      const counts: Record<string, number> = {}
      for (const e of log.filter((x) => x.t >= start)) counts[e.key] = (counts[e.key] ?? 0) + 1
      return { label, counts }
    },
  }
}

test('내 변경 뒤 다시 불러오기 횟수', async ({ page }) => {
  await page.goto('/login')
  await page.getByLabel('아이디').fill(data.user.username)
  await page.getByLabel('비밀번호', { exact: true }).fill(data.user.password)
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page).toHaveURL(/\/drive/)
  await page.waitForTimeout(1500)
  const c = counter(page)
  const results = []
  results.push(await c.after('새 폴더 만들기(개인)', async () => {
    await page.getByRole('button', { name: '새 폴더' }).click()
    await page.getByRole('dialog').getByRole('textbox').fill('중복 확인')
    await page.getByRole('dialog').getByRole('button', { name: '만들기' }).click()
  }))
  results.push(await c.after('업로드(개인)', async () => {
    await page.locator('input[type="file"]').first().setInputFiles({ name: 'dup.txt', mimeType: 'text/plain', buffer: Buffer.from('x') })
  }))
  await page.goto(`/teams/${data.teamId}/folders/${data.teamRootFolderId}`)
  await page.waitForTimeout(2000)
  results.push(await c.after('새 폴더 만들기(팀)', async () => {
    await page.getByRole('button', { name: '새 폴더' }).click()
    await page.getByRole('dialog').getByRole('textbox').fill('팀 중복 확인')
    await page.getByRole('dialog').getByRole('button', { name: '만들기' }).click()
  }))
  results.push(await c.after('알림 모두 읽음', async () => {
    await page.getByRole('button', { name: /알림/ }).first().click()
    await page.getByRole('button', { name: '모두 읽음' }).click().catch(() => {})
  }))
  writeFileSync(new URL('../../qa/results/duplicate-requests.json', import.meta.url), JSON.stringify(results, null, 2))
  console.log(JSON.stringify(results, null, 2))
})
