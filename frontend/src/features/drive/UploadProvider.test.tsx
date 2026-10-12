import { act, fireEvent, render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { uploadFile } from '@/api/upload'
import { UploadProvider, useUploads } from './UploadProvider'

vi.mock('@/auth/AuthProvider', () => ({ usePublicConfig: () => ({ maxUploadBytes: 1024 * 1024 }) }))
vi.mock('@/api/upload', () => ({ uploadFile: vi.fn() }))

function Picker() {
  const { enqueue } = useUploads()
  return <button onClick={() => enqueue(1, [new File(['hello'], '보고서.pdf')])}>올리기</button>
}

describe('UploadProvider', () => {
  it('[L-5] 파일마다 진행률을 진행 막대(progressbar)로 알린다 — 색 막대만 있어 화면 읽기 프로그램은 진행을 알 수 없었음', async () => {
    let report: (p: number) => void = () => {}
    vi.mocked(uploadFile).mockImplementation((_folder, _file, onProgress) => {
      report = onProgress
      return new Promise(() => {})
    })
    render(
      <QueryClientProvider client={new QueryClient()}>
        <UploadProvider>
          <Picker />
        </UploadProvider>
      </QueryClientProvider>,
    )

    fireEvent.click(screen.getByRole('button', { name: '올리기' }))
    await act(async () => report(0.4))

    const bar = screen.getByRole('progressbar', { name: '보고서.pdf 업로드' })
    expect(bar).toHaveAttribute('aria-valuenow', '40')
    expect(bar).toHaveAttribute('aria-valuemin', '0')
    expect(bar).toHaveAttribute('aria-valuemax', '100')
  })
})
