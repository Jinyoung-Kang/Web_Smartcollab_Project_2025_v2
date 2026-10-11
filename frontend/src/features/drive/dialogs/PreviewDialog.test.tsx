import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { configApi, fileApi } from '@/api/endpoints'
import type { Item, PublicConfig } from '@/api/types'
import { ToastProvider } from '@/components/ui/Toast'
import { PreviewDialog } from './PreviewDialog'

function item(id: number, name: string, previewKind: Item['previewKind']): Item {
  return {
    type: 'file', id, name, size: 10, extension: name.split('.').pop(), ownerName: '김하늘', createdAt: '2026-09-01T00:00:00Z',
    updatedAt: '2026-09-01T00:00:00Z', previewKind, textEditable: previewKind === 'TEXT',
  }
}

function renderPreview(file: Item, config: Partial<PublicConfig>) {
  vi.spyOn(configApi, 'get').mockResolvedValue({
    translationEnabled: false, officePreviewEnabled: false, maxUploadBytes: 1, demo: { enabled: false, accounts: [] }, aiEnabled: false, ...config,
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <ToastProvider>
        <MemoryRouter>
          <PreviewDialog file={file} onClose={vi.fn()} />
        </MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>,
  )
}

afterEach(() => vi.restoreAllMocks())

describe('PreviewDialog — 요약·번역 [AI-02]', () => {
  const ai = { translationEnabled: true, aiEnabled: true, aiModel: 'claude-haiku-5-5' }

  it('PDF 미리보기에서 AI 요약을 누르면 결과를 미리보기 옆에 보여 준다', async () => {
    const user = userEvent.setup()
    vi.spyOn(fileApi, 'summary').mockResolvedValue({ method: 'generative', model: 'claude-haiku-5-5', summary: '분기 보고서 요약', sentences: ['매출 증가'] })
    renderPreview(item(7, '보고서.pdf', 'PDF'), ai)

    const button = await screen.findByRole('button', { name: /AI 요약/ })
    await vi.waitFor(() => expect(button).toBeEnabled())
    await user.click(button)

    expect(await screen.findByText('분기 보고서 요약')).toBeInTheDocument()
    expect(screen.getByText('매출 증가')).toBeInTheDocument()
    expect(fileApi.summary).toHaveBeenCalledWith(7)
  })

  it('Word(docx) 문서도 번역할 수 있다', async () => {
    const user = userEvent.setup()
    vi.spyOn(fileApi, 'translate').mockResolvedValue({ text: 'Plan document', targetLang: 'EN', provider: 'claude', model: 'claude-haiku-5-5' })
    renderPreview(item(8, '기획.docx', 'OFFICE'), ai)

    const button = await screen.findByRole('button', { name: '번역' })
    await vi.waitFor(() => expect(button).toBeEnabled())
    await user.click(button)

    expect(await screen.findByText('Plan document')).toBeInTheDocument()
    expect(fileApi.translate).toHaveBeenCalledWith(8, 'EN')
  })

  it('이미지에는 요약·번역 도구가 없다', async () => {
    renderPreview(item(9, '사진.png', 'IMAGE'), ai)

    expect(await screen.findByRole('img', { name: '사진.png' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '번역' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /요약|핵심 문장/ })).not.toBeInTheDocument()
  })

  it('AI 키가 없으면 PDF 요약·번역은 꺼지고 이유를 알린다', async () => {
    renderPreview(item(10, '보고서.pdf', 'PDF'), { translationEnabled: true, aiEnabled: false })

    const summary = await screen.findByRole('button', { name: /핵심 문장/ })
    expect(summary).toBeDisabled()
    expect(summary).toHaveAttribute('title', expect.stringContaining('ANTHROPIC_API_KEY'))
    expect(screen.getByRole('button', { name: '번역' })).toBeDisabled()
  })

  it('동작 고정: AI 키 없이 DeepL 만 있으면 Word 는 핵심 문장만 되고, 번역은 꺼진 채 AI 키가 필요하다고 알린다', async () => {
    renderPreview(item(12, '기획.docx', 'OFFICE'), { translationEnabled: true, aiEnabled: false })

    expect(await screen.findByRole('button', { name: /핵심 문장/ })).toBeEnabled()
    const translate = screen.getByRole('button', { name: '번역' })
    // 공개 설정을 받은 뒤의 안내
    await vi.waitFor(() => expect(translate).toHaveAttribute('title', 'PDF·Word 번역은 서버에 AI API 키(ANTHROPIC_API_KEY)가 있어야 합니다'))
    expect(translate).toBeDisabled()
  })

  it('동작 고정: AI 키가 있으면 요약·번역 단추가 모델 이름과 전송 사실을 알린다', async () => {
    renderPreview(item(13, '보고서.pdf', 'PDF'), ai)

    const summary = await screen.findByRole('button', { name: /AI 요약/ })
    expect(summary).toHaveAttribute('title', 'AI(Claude Haiku 5.5)로 요약합니다. 문서 내용이 Anthropic(Claude API)으로 전송됩니다.')
    expect(screen.getByRole('button', { name: '번역' })).toHaveAttribute('title', 'AI(Claude Haiku 5.5)로 번역합니다. 문서 내용이 Anthropic(Claude API)으로 전송됩니다.')
  })

  it('AI 키 없이 DeepL 만 있으면 텍스트 문서는 번역할 수 있다', async () => {
    vi.spyOn(fileApi, 'content').mockResolvedValue({
      name: '메모.txt', folderId: 1, content: '안녕하세요', versionId: 1, editable: true, updatedAt: '2026-09-01T00:00:00Z',
    })
    renderPreview(item(11, '메모.txt', 'TEXT'), { translationEnabled: true, aiEnabled: false })

    const button = await screen.findByRole('button', { name: '번역' })
    await vi.waitFor(() => expect(button).toBeEnabled())
  })

  it('[M-5] 요약 중에는 요약 단추가 꺼져, 연달아 눌러도 요청은 한 번 — 누를 때마다 과금됐음. 번역으로 바꾸는 것은 됨', async () => {
    const user = userEvent.setup()
    const summary = vi.spyOn(fileApi, 'summary').mockReturnValue(new Promise(() => {}))
    renderPreview(item(12, '보고서.pdf', 'PDF'), ai)

    const button = await screen.findByRole('button', { name: /AI 요약/ })
    await vi.waitFor(() => expect(button).toBeEnabled())
    await user.click(button)
    await user.click(button)

    expect(summary).toHaveBeenCalledTimes(1)
    expect(button).toBeDisabled()
    expect(screen.getByRole('button', { name: '번역' })).toBeEnabled()   // 다른 작업으로 바꾸기(5단계)는 그대로
  })
})

