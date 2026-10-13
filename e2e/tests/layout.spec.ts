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
