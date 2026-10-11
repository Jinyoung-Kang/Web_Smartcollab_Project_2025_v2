import { fireEvent, render, screen } from '@testing-library/react'
import { Dialog } from './Dialog'

describe('Dialog', () => {
  it('[L-8] 입력란에서 끌어 선택하다 바깥에서 놓아도 닫히지 않는다 — 입력한 내용이 사라졌음. 바깥을 눌렀다 놓으면 닫힘', () => {
    const onClose = vi.fn()
    render(<Dialog open onClose={onClose} title="이름 바꾸기"><input aria-label="이름" /></Dialog>)
    const dialog = screen.getByRole('dialog')

    fireEvent.mouseDown(screen.getByLabelText('이름'))
    fireEvent.click(dialog)   // 끌다 놓은 click 의 대상은 두 지점의 공통 조상인 dialog
    expect(onClose).not.toHaveBeenCalled()

    fireEvent.mouseDown(dialog)
    fireEvent.click(dialog)
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})
