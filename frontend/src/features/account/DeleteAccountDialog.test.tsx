import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ToastProvider } from '@/components/ui/Toast'
import { DeleteAccountDialog } from './DeleteAccountDialog'

vi.mock('@/auth/AuthProvider', () => ({ useAuth: () => ({ endSession: vi.fn() }) }))

describe('DeleteAccountDialog', () => {
  it('[L-8] 닫았다 다시 열면 입력한 비밀번호가 남지 않는다 — 남아 있으면 다시 열자마자 탈퇴가 눌림', async () => {
    const user = userEvent.setup()
    const { rerender } = render(<ToastProvider><DeleteAccountDialog open onClose={() => {}} /></ToastProvider>)
    await user.type(screen.getByLabelText('비밀번호 확인'), 'secret-1')

    rerender(<ToastProvider><DeleteAccountDialog open={false} onClose={() => {}} /></ToastProvider>)
    rerender(<ToastProvider><DeleteAccountDialog open onClose={() => {}} /></ToastProvider>)

    expect(screen.getByLabelText('비밀번호 확인')).toHaveValue('')
  })
})
