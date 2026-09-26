import { expect, type Browser, type Page } from '@playwright/test'

export const DEMO_PASSWORD = process.env.DEMO_PASSWORD ?? 'demo1234!'

export async function login(page: Page, username: string, password = DEMO_PASSWORD) {
  await page.goto('/login')
  await page.getByLabel('아이디').fill(username)
  await page.getByLabel('비밀번호', { exact: true }).fill(password)
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page).toHaveURL(/\/drive/)
}

export async function newUserPage(browser: Browser, username: string) {
  const context = await browser.newContext()
  const page = await context.newPage()
  await login(page, username)
  return page
}

export async function openDemoTeam(page: Page) {
  await page.getByRole('link', { name: /SmartCollab 데모 팀/ }).click()
  await expect(page).toHaveURL(/\/teams\/\d+\/folders\/\d+/)
  await expect(page.getByText('실시간', { exact: true })).toBeVisible()
}
