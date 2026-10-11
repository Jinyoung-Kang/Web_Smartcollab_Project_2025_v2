import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Menu, MenuItem } from './misc'

function renderMenu() {
  render(
    <Menu label="정렬" trigger={({ open, toggle }) => <button aria-expanded={open} onClick={toggle}>정렬</button>}>
      {(close) => <MenuItem onClick={close}>이름순</MenuItem>}
    </Menu>,
  )
  return screen.getByRole('button', { name: '정렬' })
}

describe('Menu', () => {
  it('[L-8] Esc 로 닫으면 초점이 메뉴를 연 단추로 돌아온다 — 이전에는 문서 처음으로 가 키보드 사용자가 위치를 잃음', async () => {
    const user = userEvent.setup()
    const trigger = renderMenu()
    await user.click(trigger)
    screen.getByRole('menuitem', { name: '이름순' }).focus()

    await user.keyboard('{Escape}')

    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
    expect(trigger).toHaveFocus()
  })

  it('[L-8] 항목을 골라 닫아도 초점이 메뉴를 연 단추로 돌아온다', async () => {
    const user = userEvent.setup()
    const trigger = renderMenu()
    await user.click(trigger)

    await user.click(screen.getByRole('menuitem', { name: '이름순' }))

    expect(trigger).toHaveFocus()
  })
})
