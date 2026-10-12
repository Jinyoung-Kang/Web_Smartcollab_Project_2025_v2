import { expect, test } from './fixtures'
import { login } from './helpers'

// [M-7] 대화상자를 effect 에서 showModal() 로 열어, 안쪽 입력란의 초점·선택이 그보다 먼저 실행되고 showModal() 이 첫 단추(닫기)로
// 초점을 옮겼습니다. jsdom 으로는 드러나지 않아 실제 브라우저로 확인합니다.
test('[M-7] 이름 바꾸기 창을 열면 이름 칸에 초점이 가고 확장자 앞까지 골라져, 바로 입력하면 이름만 바뀐다', async ({ page }) => {
  await login(page, 'demo1')
  const name = `focus-${Date.now()}.txt`
  await page.locator('input[type=file][multiple]').setInputFiles({ name, mimeType: 'text/plain', buffer: Buffer.from('focus') })
  await expect(page.getByRole('button', { name, exact: true })).toBeVisible()

  await page.getByLabel(`${name} 선택`).check()
  await page.getByRole('button', { name: '이름 바꾸기' }).click()
  const input = page.getByRole('dialog').getByRole('textbox')

  await expect(input).toBeFocused()
  await page.keyboard.type('renamed')
  await expect(input).toHaveValue('renamed.txt')
  await page.keyboard.press('Escape')
})

test('[M-7] 새 폴더 창을 열면 이름 칸에 초점이 간다', async ({ page }) => {
  await login(page, 'demo1')
  await page.getByRole('button', { name: '새 폴더' }).click()

  await expect(page.getByRole('dialog').getByRole('textbox')).toBeFocused()
  await page.keyboard.press('Escape')
})
