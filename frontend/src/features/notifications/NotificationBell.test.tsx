import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { notificationApi } from '@/api/endpoints'
import { ApiError } from '@/api/http'
import type { AppNotification } from '@/api/types'
import { ToastProvider } from '@/components/ui/Toast'
import { NotificationBell } from './NotificationBell'

const notice = { id: 9, type: 'PERMISSION_CHANGED', content: "'데모 팀' 팀 권한 변경: 편집 권한 부여", read: false, createdAt: '2026-10-01T00:00:00Z', teamId: 3 } as AppNotification

afterEach(() => vi.restoreAllMocks())

function renderBell() {
  vi.spyOn(notificationApi, 'list').mockResolvedValue({ items: [notice], unreadCount: 1 })
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <ToastProvider>
        <MemoryRouter>
          <NotificationBell />
        </MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>,
  )
}

describe('NotificationBell', () => {
  it('[FB-08] 모두 읽음·삭제에 실패하면 조용히 넘어가지 않고 알린다', async () => {
    const user = userEvent.setup()
    renderBell()
    vi.spyOn(notificationApi, 'readAll').mockRejectedValue(new ApiError(0, 'NETWORK', '네트워크에 연결할 수 없습니다.'))
    vi.spyOn(notificationApi, 'remove').mockRejectedValue(new ApiError(404, 'NOT_FOUND', '알림을 찾을 수 없습니다.'))

    await user.click(await screen.findByRole('button', { name: /알림/ }))
    await user.click(await screen.findByRole('button', { name: '모두 읽음' }))
    expect(await screen.findByText('네트워크에 연결할 수 없습니다.')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '삭제' }))
    expect(await screen.findByText('알림을 찾을 수 없습니다.')).toBeInTheDocument()
  })
})
