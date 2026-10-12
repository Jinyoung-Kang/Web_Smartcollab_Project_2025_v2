import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { fileApi, teamApi } from '@/api/endpoints'
import type { Item, TeamDetail } from '@/api/types'
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

  it('결과의 경로는 그 파일이 있는 폴더 화면으로 이어진다 — 내 드라이브·팀 스토리지 (리팩터링 전 동작 고정)', async () => {
    const file: Item = { type: 'file', id: 1, name: '보고서.txt', size: 1, ownerName: '김하늘', createdAt: '2026-09-01T00:00:00Z',
      updatedAt: '2026-09-01T00:00:00Z', previewKind: 'TEXT', textEditable: true }
    vi.spyOn(fileApi, 'search').mockResolvedValue([{ item: file, folderId: 7, path: '/자료' }])
    vi.spyOn(teamApi, 'detail').mockResolvedValue({ id: 3, name: '데모 팀' } as TeamDetail)
    for (const [url, href] of [['/search?q=보고서', '/drive/7'], ['/search?q=보고서&teamId=3', '/teams/3/folders/7']] as const) {
      const router = createMemoryRouter([{ path: '/search', element: <SearchPage /> }], { initialEntries: [url] })
      const view = render(
        <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
          <RouterProvider router={router} />
        </QueryClientProvider>,
      )
      expect(await screen.findByRole('link', { name: '/자료' })).toHaveAttribute('href', href)
      view.unmount()
    }
  })
})
