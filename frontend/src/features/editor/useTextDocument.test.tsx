import { act, renderHook } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { fileApi } from '@/api/endpoints'
import { queryKeys } from '@/api/queryKeys'
import type { TextContent } from '@/api/types'
import { ToastProvider } from '@/components/ui/Toast'
import { useTextDocument } from './useTextDocument'

const doc: TextContent = { name: '메모.txt', folderId: 5, content: '처음', versionId: 1, editable: true, updatedAt: '2026-10-01T00:00:00Z' }

function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>
      <ToastProvider>{children}</ToastProvider>
    </QueryClientProvider>
  )
  return { client, ...renderHook(() => useTextDocument(1, doc), { wrapper }) }
}

afterEach(() => vi.restoreAllMocks())

describe('useTextDocument', () => {
  it('[L-8] 버전 창을 닫은 뒤 최신 내용을 받는 사이에 입력했으면, 응답이 와도 입력을 덮지 않는다', async () => {
    let finish: (c: TextContent) => void = () => {}
    vi.spyOn(fileApi, 'content').mockReturnValue(new Promise((resolve) => { finish = resolve }))
    const { result } = setup()

    act(() => result.current.reloadIfClean())
    act(() => result.current.setDraft('처음 + 새로 쓴 글'))
    await act(async () => finish({ ...doc, content: '되돌린 버전', versionId: 3 }))

    expect(result.current.draft).toBe('처음 + 새로 쓴 글')
  })

  it('[L-8] 충돌을 내 내용으로 해결하면 버전·폴더·사용량 캐시를 새로 고치고, 연달아 눌러도 한 번만 저장한다', async () => {
    vi.spyOn(fileApi, 'content').mockResolvedValue({ ...doc, content: '상대 내용', versionId: 2 })
    let finish: () => void = () => {}
    const save = vi.spyOn(fileApi, 'save').mockReturnValue(new Promise((resolve) => {
      finish = () => resolve({ versionId: 3, updatedAt: '2026-10-02T00:00:00Z' })
    }))
    const { result, client } = setup()
    client.setQueryData(queryKeys.versions.of(1), [])
    client.setQueryData(queryKeys.usage.of('personal'), { usedBytes: 1 })

    act(() => result.current.setDraft('내 내용'))
    await act(async () => {
      void result.current.overwrite()
      void result.current.overwrite()
      await Promise.resolve()
    })
    await act(async () => finish())

    expect(save).toHaveBeenCalledTimes(1)
    expect(client.getQueryState(queryKeys.versions.of(1))?.isInvalidated).toBe(true)
    expect(client.getQueryState(queryKeys.usage.of('personal'))?.isInvalidated).toBe(true)
  })

  it('[L-8] 저장하면 사용량도 새로 고친다 — 옛 버전도 저장 공간에 들어가 저장마다 바뀜', async () => {
    vi.spyOn(fileApi, 'save').mockResolvedValue({ versionId: 2, updatedAt: '2026-10-02T00:00:00Z' })
    const { result, client } = setup()
    client.setQueryData(queryKeys.usage.of('personal'), { usedBytes: 1 })

    act(() => result.current.setDraft('고친 내용'))
    await act(async () => { await result.current.save() })

    expect(client.getQueryState(queryKeys.usage.of('personal'))?.isInvalidated).toBe(true)
  })
})
