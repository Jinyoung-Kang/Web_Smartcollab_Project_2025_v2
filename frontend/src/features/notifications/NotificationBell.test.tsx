import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { notificationApi, teamApi } from '@/api/endpoints'
import { ApiError } from '@/api/http'
import type { AppNotification } from '@/api/types'
import { ToastProvider } from '@/components/ui/Toast'
import { NotificationBell } from './NotificationBell'

const notice = { id: 9, type: 'PERMISSION_CHANGED', content: "'데모 팀' 팀 권한 변경: 편집 권한 부여", read: false, createdAt: '2026-10-01T00:00:00Z', teamId: 3 } as AppNotification

afterEach(() => vi.restoreAllMocks())

const invite = (id: number, invitationId: number, team: string) => ({
  id, type: 'TEAM_INVITE', content: `'${team}' 팀에 초대했습니다.`, read: false, createdAt: '2026-10-01T00:00:00Z',
  invitationId, invitationStatus: 'PENDING', teamId: invitationId,
}) as AppNotification

function renderBell(items: AppNotification[] = [notice]) {
  vi.spyOn(notificationApi, 'list').mockResolvedValue({ items, unreadCount: items.length })
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

  it('[L-5] 초대에 답하는 동안 그 초대의 수락·거절을 모두 막고, 다른 초대의 단추는 진행 중으로 보이지 않는다', async () => {
    const user = userEvent.setup()
    renderBell([invite(1, 11, '디자인'), invite(2, 12, '개발')])
    vi.spyOn(teamApi, 'acceptInvitation').mockReturnValue(new Promise(() => {}))
    const reject = vi.spyOn(teamApi, 'rejectInvitation').mockResolvedValue(undefined)

    await user.click(await screen.findByRole('button', { name: /알림/ }))
    const [designAccept, devAccept] = await screen.findAllByRole('button', { name: '수락' })
    const [designReject, devReject] = screen.getAllByRole('button', { name: '거절' })
    await user.click(designAccept!)

    expect(designAccept).toBeDisabled()
    expect(designReject).toBeDisabled()
    expect(devAccept).toBeEnabled()
    expect(devReject).toBeEnabled()
    await user.click(designReject!)
    expect(reject).not.toHaveBeenCalled()
  })
})
