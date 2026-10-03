import { render } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { teamApi } from '@/api/endpoints'
import { ToastProvider } from '@/components/ui/Toast'
import { TeamActivityProvider } from './TeamActivity'

const realtime = vi.hoisted(() => ({ connected: true, subscribe: () => () => {}, publish: () => false }))
vi.mock('@/auth/AuthProvider', () => ({ useMe: () => ({ id: 1, username: 'demo1' }) }))
vi.mock('./RealtimeProvider', () => ({ useRealtime: () => realtime, useSubscription: () => {} }))

afterEach(() => vi.restoreAllMocks())

describe('TeamActivityProvider', () => {
  it('[FB-05] 실시간 연결이 끊겼다 다시 이어지면 끊긴 동안 온 채팅·알림을 다시 불러온다', () => {
    vi.spyOn(teamApi, 'list').mockResolvedValue([])
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    const app = () => (
      <QueryClientProvider client={client}>
        <ToastProvider>
          <MemoryRouter>
            <TeamActivityProvider>
              <p>앱</p>
            </TeamActivityProvider>
          </MemoryRouter>
        </ToastProvider>
      </QueryClientProvider>
    )
    const view = render(app())
    realtime.connected = false
    view.rerender(app())
    realtime.connected = true
    view.rerender(app())

    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['chat'] })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['notifications'] })
  })
})
