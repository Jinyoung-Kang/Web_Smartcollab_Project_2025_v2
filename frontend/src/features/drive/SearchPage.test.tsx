import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { fileApi } from '@/api/endpoints'
import SearchPage from './SearchPage'

vi.mock('./dialogs/PreviewDialog', () => ({ PreviewDialog: () => null }))

afterEach(() => vi.restoreAllMocks())

describe('SearchPage', () => {
  it('[L-8] 검색에 실패하면 알린다 — 이전에는 결과도 안내도 없는 빈 화면', async () => {
    vi.spyOn(fileApi, 'search').mockRejectedValue(new Error('서버 오류'))
    const router = createMemoryRouter([{ path: '/search', element: <SearchPage /> }], { initialEntries: ['/search?q=보고서'] })
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    )

    expect(await screen.findByRole('alert')).toHaveTextContent('검색하지 못했습니다')
  })
})
