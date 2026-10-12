import { fireEvent, render, screen } from '@testing-library/react'
import { Dialog } from './Dialog'
import { ToastProvider, useToast } from './Toast'

function SaveInDialog({ open }: { open: boolean }) {
  const toast = useToast()
  return (
    <>
      <button onClick={() => toast.error('바깥 알림')}>바깥</button>
      <Dialog open={open} onClose={() => {}} title="이름 바꾸기">
        <button onClick={() => toast.error('저장하지 못했습니다.')}>저장</button>
      </Dialog>
    </>
  )
}

describe('ToastProvider', () => {
  it('[L-5] 모달이 열려 있으면 알림을 모달 안에 띄운다 — 바깥에 두면 모달 배경 뒤에 가려 닫을 수 없고 화면 낭독기도 읽지 않음', () => {
    render(<ToastProvider><SaveInDialog open /></ToastProvider>)

    fireEvent.click(screen.getByRole('button', { name: '저장' }))

    const dialog = document.querySelector('dialog')!
    expect(dialog).toContainElement(screen.getByRole('alert'))
  })

  it('[L-5] 모달이 없으면 문서 쪽에 띄운다', () => {
    render(<ToastProvider><SaveInDialog open={false} /></ToastProvider>)

    fireEvent.click(screen.getByRole('button', { name: '바깥' }))

    expect(document.querySelector('dialog')).not.toContainElement(screen.getByRole('alert'))
    expect(screen.getByRole('alert')).toHaveTextContent('바깥 알림')
  })
})
