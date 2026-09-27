import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { authApi, configApi, fileApi } from '@/api/endpoints'
import { ApiError, notifyUnauthorized } from '@/api/http'
import type { Me, PublicConfig, TextContent } from '@/api/types'
import { ConfirmProvider } from '@/components/ui/Confirm'
import { ToastProvider } from '@/components/ui/Toast'
import { App } from '@/App'
import { AuthProvider } from './AuthProvider'

// 로그인 후 화면은 가짜로 바꾸고, 인증 흐름(AuthProvider·RequireAuth·LoginPage·데이터 라우터)은 실제 코드로 검증합니다.
vi.mock('@/layout/AppShell', async () => {
  const { useState } = await import('react')
  const { Outlet } = await import('react-router')
  const { useAuth } = await import('./AuthProvider')
  const { DeleteAccountDialog } = await import('@/features/account/DeleteAccountDialog')
  return {
    AppShell() {
      const { logout } = useAuth()
      const [deleting, setDeleting] = useState(false)
      return (
        <div>
          <button onClick={() => void logout()}>로그아웃</button>
          <button onClick={() => setDeleting(true)}>회원 탈퇴</button>
          <DeleteAccountDialog open={deleting} onClose={() => setDeleting(false)} />
          <Outlet />
        </div>
      )
    },
  }
})
vi.mock('@/features/drive/DrivePage', () => ({
  DrivePage: () => <p>드라이브 화면</p>,
  TeamRootRedirect: () => null,
}))

const me: Me = { id: 1, username: 'demo1', name: '김하늘', rootFolderId: 10, createdAt: '2026-09-01T00:00:00Z' }
const config: PublicConfig = {
  translationEnabled: false, officePreviewEnabled: false, maxUploadBytes: 0, demo: { enabled: false, accounts: [] },
}

/** main.tsx 와 같은 구성: 데이터 라우터의 경로 요소 안에 공급자와 App 을 둡니다. */
function renderApp(path: string, { loggedIn = true } = {}) {
  if (loggedIn) vi.spyOn(authApi, 'me').mockResolvedValue(me)
  else vi.spyOn(authApi, 'me').mockRejectedValue(new ApiError(401, 'UNAUTHORIZED', '로그인이 필요합니다.'))
  vi.spyOn(configApi, 'get').mockResolvedValue(config)
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createMemoryRouter(
    [
      {
        path: '*',
        element: (
          <QueryClientProvider client={client}>
            <ToastProvider>
              <ConfirmProvider>
                <AuthProvider>
                  <App />
                </AuthProvider>
              </ConfirmProvider>
            </ToastProvider>
          </QueryClientProvider>
        ),
      },
    ],
    { initialEntries: [path] },
  )
  render(<RouterProvider router={router} />)
  return { router, client }
}

/** 화면이 로그인 화면에 머무는지 보려면, 리다이렉트가 일어날 시간을 준 뒤 확인합니다. */
const settle = () => act(() => new Promise((resolve) => setTimeout(resolve, 50)))

afterEach(() => vi.restoreAllMocks())

describe('로그인 상태가 끝날 때 [BUG-09]', () => {
  it('로그아웃하면 로그인 화면이 보이고, 드라이브로 되돌아가지 않으며, 이전 사용자의 데이터를 남기지 않는다', async () => {
    const user = userEvent.setup()
    const { router, client } = renderApp('/drive')
    await screen.findByText('드라이브 화면')
    client.setQueryData(['teams'], [{ id: 3, name: '이전 사용자의 팀' }])
    vi.spyOn(authApi, 'logout').mockResolvedValue(undefined)

    await user.click(screen.getByRole('button', { name: '로그아웃' }))

    expect(await screen.findByLabelText('아이디')).toBeInTheDocument()
    await settle()
    expect(router.state.location.pathname).toBe('/login')
    expect(screen.getByLabelText('아이디')).toBeInTheDocument()
    expect(screen.queryByText('드라이브 화면')).not.toBeInTheDocument()
    expect(client.getQueryData(['teams'])).toBeUndefined()
    expect(client.getQueryData(['me'])).toBeNull()
  })

  it('세션이 만료되어 요청이 401 이면 로그인 화면으로 간다', async () => {
    const { router, client } = renderApp('/drive')
    await screen.findByText('드라이브 화면')
    client.setQueryData(['teams'], [{ id: 3, name: '이전 사용자의 팀' }])

    act(() => notifyUnauthorized())

    expect(await screen.findByLabelText('아이디')).toBeInTheDocument()
    await settle()
    expect(router.state.location.pathname).toBe('/login')
    expect(client.getQueryData(['teams'])).toBeUndefined()
  })

  it('편집기에 저장하지 않은 변경이 있으면 세션이 만료돼도 먼저 확인하고, 취소하면 편집 내용이 남는다', async () => {
    const user = userEvent.setup()
    const doc: TextContent = {
      name: '회의록.md', folderId: 5, content: '처음 내용', versionId: 1, editable: true, updatedAt: '2026-09-27T00:00:00Z',
    }
    vi.spyOn(fileApi, 'content').mockResolvedValue(doc)
    const { router } = renderApp('/files/1/edit')
    await user.type(await screen.findByLabelText('문서 내용'), ' 추가')

    act(() => notifyUnauthorized())

    expect(await screen.findByText('저장하지 않은 변경이 있습니다')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '취소' }))
    expect(router.state.location.pathname).toBe('/files/1/edit')
    expect(screen.getByLabelText('문서 내용')).toHaveValue('처음 내용 추가')

    act(() => notifyUnauthorized())
    await user.click(await screen.findByRole('button', { name: '저장 안 하고 나가기' }))
    expect(await screen.findByLabelText('아이디')).toBeInTheDocument()
    await settle()
    expect(router.state.location.pathname).toBe('/login')
  })

  it('탈퇴하면 안내와 함께 로그인 화면으로 간다', async () => {
    const user = userEvent.setup()
    const { router } = renderApp('/drive')
    await screen.findByText('드라이브 화면')
    vi.spyOn(authApi, 'deleteAccount').mockResolvedValue(undefined)

    await user.click(screen.getByRole('button', { name: '회원 탈퇴' }))
    await user.type(screen.getByLabelText('비밀번호 확인'), 'pass1234')
    await user.click(screen.getByRole('button', { name: '탈퇴하기' }))

    expect(await screen.findByLabelText('아이디')).toBeInTheDocument()
    await settle()
    expect(router.state.location.pathname).toBe('/login')
    expect(screen.getByText(/탈퇴가 완료되었습니다/)).toBeInTheDocument()
  })

  it('비밀번호를 한 번 틀려도(401) 올바른 비밀번호로 로그인되고, 원래 가려던 화면으로 간다', async () => {
    const user = userEvent.setup()
    const { router } = renderApp('/drive/7', { loggedIn: false })
    await screen.findByLabelText('아이디')
    vi.spyOn(authApi, 'login')
      // http.request 와 같은 순서: 401 을 알린 뒤 오류를 던집니다.
      .mockImplementationOnce(async () => {
        notifyUnauthorized()
        throw new ApiError(401, 'INVALID_CREDENTIALS', '아이디 또는 비밀번호가 일치하지 않습니다.')
      })
      .mockResolvedValueOnce(me)

    await user.type(screen.getByLabelText('아이디'), 'demo1')
    await user.type(screen.getByLabelText('비밀번호'), 'wrong-pass1')
    await user.click(screen.getByRole('button', { name: '로그인' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('일치하지 않습니다')

    await user.click(screen.getByRole('button', { name: '로그인' }))

    expect(await screen.findByText('드라이브 화면')).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/drive/7')
  })
})
