import { render, screen } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { AppErrorPage } from './AppErrorPage'

function Boom({ error }: { error: Error }): never {
  throw error
}

function renderWithError(error: Error) {
  const router = createMemoryRouter([{ path: '*', element: <Boom error={error} />, errorElement: <AppErrorPage /> }])
  render(<RouterProvider router={router} />)
}

beforeEach(() => vi.spyOn(console, 'error').mockImplementation(() => {}))
afterEach(() => vi.restoreAllMocks())

describe('AppErrorPage [ARC-04]', () => {
  it('화면을 그리다 오류가 나면 빈 화면 대신 안내와 새로고침·내 드라이브 이동을 보여 준다', () => {
    renderWithError(new Error('boom'))

    expect(screen.getByRole('heading', { level: 1, name: '화면을 표시하지 못했습니다' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '새로고침' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '내 드라이브로' })).toHaveAttribute('href', '/drive')
  })

  it('[L-5] 화면 조각(JS)을 받지 못하면 새 배포와 접근 코드 확인 만료를 함께 안내하고 새로고침을 권한다 — 잠금이 다시 걸려도 "새 버전" 이라고만 했음', () => {
    renderWithError(new TypeError('Failed to fetch dynamically imported module: http://localhost/assets/EditorPage-abc.js'))

    expect(screen.getByRole('heading', { level: 1, name: '화면을 새로 불러와야 합니다' })).toBeInTheDocument()
    expect(screen.getByText(/새 버전이 배포되었거나 접근 코드 확인 시간이 지나/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '새로고침' })).toBeInTheDocument()
  })
})
