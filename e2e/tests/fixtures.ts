import { test as base, expect, type Page } from '@playwright/test'

/** 이번 테스트에서 브라우저가 보고한 CSP(Content-Security-Policy) 위반 */
const cspViolations: string[] = []

/** 페이지의 CSP 위반 보고(콘솔 오류)를 모읍니다. 테스트가 따로 연 페이지(다른 사용자 등)에도 붙입니다. */
export function watchCsp(page: Page) {
  page.on('console', (message) => {
    if (message.type() === 'error' && /Content Security Policy/i.test(message.text())) {
      cspViolations.push(`${page.url()} :: ${message.text()}`)
    }
  })
}

/**
 * 모든 E2E 테스트는 CSP 위반이 하나라도 있으면 실패합니다 [SEC-12].
 * CSP 를 좁힌 뒤(인라인 스타일 금지) 화면 어딘가가 막혀도 조용히 깨지지 않도록 하기 위함입니다.
 */
export const test = base.extend<{ cspGuard: void }>({
  cspGuard: [
    async ({ page }, use) => {
      cspViolations.length = 0
      watchCsp(page)
      await use()
      expect(cspViolations, 'CSP 위반').toEqual([])
    },
    { auto: true },
  ],
})

export { expect }
