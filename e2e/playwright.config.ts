import { defineConfig } from '@playwright/test'

// 실행 중인 전체 스택(docker compose, DEMO_ENABLED=true)에 대해 사용자 시나리오를 검증합니다.
export default defineConfig({
  testDir: './tests',
  timeout: 60_000,
  workers: 1,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:8080',
    locale: 'ko-KR',
    timezoneId: 'Asia/Seoul',
    viewport: { width: 1440, height: 900 },
    trace: 'retain-on-failure',
    channel: process.env.CI ? undefined : 'chrome',
  },
})
