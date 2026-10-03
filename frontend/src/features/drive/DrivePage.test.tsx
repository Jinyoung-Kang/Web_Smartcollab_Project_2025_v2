import { act, fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useEffect } from 'react'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { folderApi, itemApi } from '@/api/endpoints'
import { ApiError } from '@/api/http'
import { queryKeys } from '@/api/queryKeys'
import type { FolderContents } from '@/api/types'
import { ConfirmProvider } from '@/components/ui/Confirm'
import { ToastProvider } from '@/components/ui/Toast'
import { DrivePage } from './DrivePage'

const panel = vi.hoisted(() => ({ mounts: 0, lastVisible: undefined as boolean | undefined }))
vi.mock('@/auth/AuthProvider', () => ({
  useMe: () => ({ id: 1, username: 'demo1', name: '김하늘', rootFolderId: 1 }),
  usePublicConfig: () => ({ translationEnabled: false, officePreviewEnabled: false, maxUploadBytes: 1024, demo: { enabled: false, accounts: [] } }),
}))
const uploads = vi.hoisted(() => ({ enqueue: vi.fn() }))
vi.mock('./UploadProvider', () => ({ useUploads: () => uploads }))
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

let client: QueryClient

function renderDrive(wide: boolean, path = '/teams/3/folders/10') {
  panel.mounts = 0
  window.matchMedia = ((query: string) => ({
    matches: wide, media: query, addEventListener: () => {}, removeEventListener: () => {},
  })) as unknown as typeof window.matchMedia
  vi.spyOn(folderApi, 'contents').mockImplementation(async (id: number) => contents(id))
  const router = createMemoryRouter([
    { path: '/teams/:teamId/folders/:folderId', element: <DrivePage /> },
    { path: '/drive/:folderId', element: <DrivePage /> },
  ], { initialEntries: [path] })
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
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

describe('DrivePage — 변경 뒤 다시 불러오기', () => {
  it('[5단계] 지운 뒤 휴지통 목록도 바로 다시 불러온다 (이전: 캐시 유효 시간 30초 동안 옛 목록)', async () => {
    vi.spyOn(itemApi, 'remove').mockResolvedValue({ trashedFiles: 0, trashedFolders: 1 })
    renderDrive(true)
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    const user = userEvent.setup()
    await user.click(await screen.findByLabelText('회의록 선택'))
    await user.keyboard('{Delete}')
    const dialog = await screen.findByRole('dialog', { name: '1개 항목을 삭제할까요?' })
    await user.click(within(dialog).getByRole('button', { name: '삭제' }))

    await screen.findByText('1개 항목을 휴지통으로 옮겼습니다.')
    expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.trash.all })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.folder.of(10) })
  })
})

describe('DrivePage — 다른 폴더로 옮긴 뒤 끝난 요청 (독립 검토)', () => {
  it('지우는 동안 다른 폴더로 옮겨도, 끝나면 지운 폴더를 다시 불러온다 (이전: 지금 보는 폴더를 갱신)', async () => {
    let finish: (r: { trashedFiles: number; trashedFolders: number }) => void = () => {}
    vi.spyOn(itemApi, 'remove').mockReturnValue(new Promise((resolve) => { finish = resolve }))
    const router = renderDrive(true)
    const user = userEvent.setup()
    await user.click(await screen.findByLabelText('회의록 선택'))
    await user.keyboard('{Delete}')
    const dialog = await screen.findByRole('dialog', { name: '1개 항목을 삭제할까요?' })
    await user.click(within(dialog).getByRole('button', { name: '삭제' }))
    await act(() => router.navigate('/teams/3/folders/11'))
    await screen.findByText('하위 폴더')
    const invalidate = vi.spyOn(client, 'invalidateQueries')

    await act(async () => finish({ trashedFiles: 0, trashedFolders: 1 }))

    expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.folder.of(10) })
    expect(invalidate).not.toHaveBeenCalledWith({ queryKey: queryKeys.folder.of(11) })
  })
})

describe('DrivePage — 지금 동작 고정 (구조 정리 전)', () => {
  it('폴더 이름을 누르면 그 폴더로 들어간다', async () => {
    const router = renderDrive(true)
    await userEvent.setup().click(await screen.findByRole('button', { name: '회의록' }))
    expect(router.state.location.pathname).toBe('/teams/3/folders/11')
  })

  it('새 폴더는 지금 폴더 아래에 만들고 알린다', async () => {
    const create = vi.spyOn(folderApi, 'create').mockResolvedValue(contents(12).items[0] ?? ({} as never))
    renderDrive(true)
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: /새 폴더/ }))
    const dialog = await screen.findByRole('dialog', { name: '새 폴더' })
    const input = within(dialog).getByRole('textbox')
    await user.clear(input)
    await user.type(input, '기획')
    await user.click(within(dialog).getByRole('button', { name: '만들기' }))

    expect(await screen.findByText("'기획' 폴더를 만들었습니다.")).toBeInTheDocument()
    expect(create).toHaveBeenCalledWith(10, '기획')
  })

  it('고른 항목을 이동하면 고른 대상 폴더로 옮기고 알린다', async () => {
    vi.spyOn(folderApi, 'tree').mockResolvedValue({ roots: [{ id: 10, name: '데모 팀', children: [{ id: 20, name: '보관함', children: [] }] }] })
    const move = vi.spyOn(itemApi, 'move').mockResolvedValue(undefined as never)
    renderDrive(true)
    const user = userEvent.setup()
    await user.click(await screen.findByLabelText('회의록 선택'))
    await user.click(screen.getByRole('button', { name: /이동/ }))
    await user.click(await screen.findByRole('button', { name: '보관함' }))
    await user.click(screen.getByRole('button', { name: '여기로 이동' }))

    expect(await screen.findByText('1개 항목을 옮겼습니다.')).toBeInTheDocument()
    expect(move).toHaveBeenCalledWith([{ type: 'folder', id: 11 }], 20)
  })

  it('파일을 끌어다 놓으면 지금 폴더로 올린다', async () => {
    uploads.enqueue.mockClear()
    renderDrive(true)
    await screen.findByRole('button', { name: '회의록' })
    const file = new File(['x'], '자료.txt', { type: 'text/plain' })
    const region = screen.getByRole('button', { name: '회의록' }).closest('section')!
    fireEvent.drop(region, { dataTransfer: { files: [file], types: ['Files'] } })
    expect(uploads.enqueue).toHaveBeenCalledWith(10, [file])
  })

  it('주소의 스코프가 실제 폴더와 다르면 올바른 주소로 바로잡는다', async () => {
    const router = renderDrive(true, '/drive/10')
    await vi.waitFor(() => expect(router.state.location.pathname).toBe('/teams/3/folders/10'))
  })

  it('없거나 볼 수 없는 폴더면 안내한다', async () => {
    renderDrive(true)
    vi.mocked(folderApi.contents).mockRejectedValue(new ApiError(404, 'NOT_FOUND', '폴더를 찾을 수 없습니다.'))
    await act(() => client.resetQueries())
    expect(await screen.findByText('폴더를 찾을 수 없습니다')).toBeInTheDocument()
  })
})

