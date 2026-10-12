import { QueryClient } from '@tanstack/react-query'
import { queryKeys } from './queryKeys'
import { scheduleDriveChange } from './driveCache'

afterEach(() => vi.useRealTimers())

describe('scheduleDriveChange [M-9]', () => {
  it('업로드 묶음처럼 짧은 시간에 몰린 변경은 합쳐서 한 번만 다시 받는다 (이전: 파일 100개면 목록 100번·사용량 100번)', () => {
    vi.useFakeTimers()
    const qc = new QueryClient()
    const invalidate = vi.spyOn(qc, 'invalidateQueries')

    for (let i = 0; i < 100; i++) scheduleDriveChange(qc, { folderIds: [7] })
    vi.advanceTimersByTime(1000)

    const calls = (key: readonly unknown[]) => invalidate.mock.calls.filter(([f]) => JSON.stringify(f?.queryKey) === JSON.stringify(key)).length
    expect(calls(queryKeys.folder.of(7))).toBe(1)
    expect(calls(queryKeys.usage.all)).toBe(1)
  })

  it('여러 폴더·스토리지의 변경을 합치면 그 폴더들을 모두 다시 받고, 스토리지가 섞이면 모든 스토리지의 트리·사용량을 받는다', () => {
    vi.useFakeTimers()
    const qc = new QueryClient()
    const invalidate = vi.spyOn(qc, 'invalidateQueries')

    scheduleDriveChange(qc, { folderIds: [1], scope: 3 })
    scheduleDriveChange(qc, { folderIds: [2], scope: 'personal' })
    vi.advanceTimersByTime(1000)

    expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.folder.of(1) })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.folder.of(2) })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.usage.all })
  })
})
