import { defineConfig } from '@playwright/test'

// QA 점검용(출시 기준 QA, docs/QA_2026-10-03.md). CI 의 E2E(tests/)와 분리해 QA 스택(http://localhost:8080)에서만 실행합니다.
// 실행: cd e2e && npx playwright test -c playwright.qa.config.ts
export default defineConfig({
  testDir: './qa',
  timeout: 600_000,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: process.env.QA_BASE_URL ?? 'http://localhost:8080',
    locale: 'ko-KR',
    timezoneId: 'Asia/Seoul',
    viewport: { width: 1440, height: 900 },
    channel: process.env.CI ? undefined : 'chrome',
  },
})
