import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useEffect } from 'react'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { folderApi } from '@/api/endpoints'
import type { FolderContents } from '@/api/types'
import { ConfirmProvider } from '@/components/ui/Confirm'
import { ToastProvider } from '@/components/ui/Toast'
import { DrivePage } from './DrivePage'

const panel = vi.hoisted(() => ({ mounts: 0, lastVisible: undefined as boolean | undefined }))
vi.mock('@/auth/AuthProvider', () => ({
  useMe: () => ({ id: 1, username: 'demo1', name: '김하늘', rootFolderId: 1 }),
  usePublicConfig: () => ({ translationEnabled: false, officePreviewEnabled: false, maxUploadBytes: 1024, demo: { enabled: false, accounts: [] } }),
}))
vi.mock('./UploadProvider', () => ({ useUploads: () => ({ enqueue: () => {} }) }))
vi.mock('@/features/team/TeamPanel', () => ({
  TeamPanel: ({ visible }: { teamId: number; visible?: boolean }) => {
    useEffect(() => {
      panel.mounts++
    }, [])
    panel.lastVisible = visible
    return <p>팀 패널</p>
  },
}))

const contents = (id: number): FolderContents => ({
  folder: { id, name: `폴더 ${id}`, teamId: 3, root: id === 10 },
  path: id === 10 ? [{ id: 10, name: '데모 팀' }] : [{ id: 10, name: '데모 팀' }, { id, name: '하위 폴더' }],
  items: id === 10 ? [{ type: 'folder', id: 11, name: '회의록', ownerName: '김하늘', createdAt: '2026-09-01T00:00:00Z',
    updatedAt: '2026-09-01T00:00:00Z', previewKind: 'NONE', textEditable: false }] : [],
  permissions: { canEdit: true, canDelete: true, canInvite: true, leader: true },
})

function renderDrive(wide: boolean) {
  panel.mounts = 0
  window.matchMedia = ((query: string) => ({
    matches: wide, media: query, addEventListener: () => {}, removeEventListener: () => {},
  })) as unknown as typeof window.matchMedia
  vi.spyOn(folderApi, 'contents').mockImplementation(async (id: number) => contents(id))
  const router = createMemoryRouter([{ path: '/teams/:teamId/folders/:folderId', element: <DrivePage /> }],
    { initialEntries: ['/teams/3/folders/10'] })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <ToastProvider>
        <ConfirmProvider>
          <RouterProvider router={router} />
        </ConfirmProvider>
      </ToastProvider>
    </QueryClientProvider>,
  )
  return router
}

afterEach(() => vi.restoreAllMocks())

describe('DrivePage — 팀 패널', () => {
  it('[FB-07] 같은 팀의 하위 폴더로 옮겨도 팀 패널을 다시 만들지 않는다 (입력 중이던 채팅·스크롤 유지)', async () => {
    const router = renderDrive(true)
    expect(await screen.findByRole('button', { name: '회의록' })).toBeInTheDocument()
    expect(panel.mounts).toBe(1)

    await act(() => router.navigate('/teams/3/folders/11'))

    expect(await screen.findByText('하위 폴더')).toBeInTheDocument()
    expect(panel.mounts).toBe(1)
  })

  it('[FB-06] 좁은 화면에서 팀 패널을 열기 전에는 채팅을 보는 중으로 치지 않는다', async () => {
    renderDrive(false)
    await screen.findByRole('button', { name: '회의록' })
    expect(panel.lastVisible).toBe(false)

    await userEvent.setup().click(screen.getByRole('button', { name: /팀 채팅·멤버/ }))

    expect(panel.lastVisible).toBe(true)
  })
})
