import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import { Download, FileQuestion, PencilLine } from 'lucide-react'
import { fileApi } from '@/api/endpoints'
import type { Item } from '@/api/types'
import { usePublicConfig } from '@/auth/AuthProvider'
import { Button, buttonStyles } from '@/components/ui/Button'
import { Dialog } from '@/components/ui/Dialog'
import { EmptyState, Spinner } from '@/components/ui/misc'
import { formatBytes } from '@/lib/format'

/**
 * 미리보기. 이미지·PDF 는 인증 쿠키로 바로 불러오고(v1: 파일 전체를 JS 메모리로 받아 blob URL 생성 후 해제하지 않음),
 * 텍스트는 편집기로 이어지며, Office 문서는 Azure 저장소일 때만 온라인 뷰어를 씁니다.
 */
export function PreviewDialog({ file, onClose }: { file: Item | null; onClose: () => void }) {
  const navigate = useNavigate()
  const config = usePublicConfig()
  const kind = file?.previewKind ?? 'NONE'

  const text = useQuery({
    queryKey: ['file-content', file?.id],
    queryFn: () => fileApi.content(file!.id),
    enabled: !!file && kind === 'TEXT',
  })
  const office = useQuery({
    queryKey: ['office-url', file?.id],
    queryFn: () => fileApi.officePreviewUrl(file!.id),
    enabled: !!file && kind === 'OFFICE' && !!config?.officePreviewEnabled,
    staleTime: 5 * 60_000,
    retry: false,
  })

  const download = file ? (
    <a href={fileApi.downloadUrl(file.id)} download className={buttonStyles()}>
      <Download className="size-4" /> 내려받기
    </a>
  ) : null

  return (
    <Dialog
      open={!!file}
      onClose={onClose}
      title={file?.name}
      description={file?.size !== undefined ? formatBytes(file.size) : undefined}
      size="xl"
      footer={
        <>
          {download}
          {file?.textEditable && (
            <Button variant="primary" onClick={() => navigate(`/files/${file.id}/edit`)}>
              <PencilLine className="size-4" /> 편집기로 열기
            </Button>
          )}
        </>
      }
    >
      <div className="flex min-h-[50vh] items-center justify-center rounded-lg bg-slate-50">
        {file && kind === 'IMAGE' && (
          <img src={fileApi.viewUrl(file.id)} alt={file.name} className="max-h-[65vh] max-w-full object-contain" />
        )}
        {file && kind === 'PDF' && (
          <iframe src={fileApi.viewUrl(file.id)} title={file.name} className="h-[65vh] w-full rounded-lg bg-white" />
        )}
        {kind === 'TEXT' && (
          text.isPending ? <Spinner /> : text.error ? (
            <p className="text-sm text-red-600">{(text.error as Error).message}</p>
          ) : (
            <pre className="h-[65vh] w-full overflow-auto rounded-lg bg-white p-5 font-mono text-sm leading-relaxed whitespace-pre-wrap text-slate-700">
              {text.data?.content}
            </pre>
          )
        )}
        {kind === 'OFFICE' && (
          config?.officePreviewEnabled ? (
            office.data ? (
              <iframe
                title={file?.name}
                className="h-[65vh] w-full rounded-lg bg-white"
                src={`https://view.officeapps.live.com/op/embed.aspx?src=${encodeURIComponent(office.data.url)}`}
              />
            ) : office.error ? (
              <p className="text-sm text-red-600">{(office.error as Error).message}</p>
            ) : (
              <Spinner />
            )
          ) : (
            <EmptyState icon={FileQuestion} title="이 서버에서는 Office 미리보기를 지원하지 않습니다"
              description="클라우드(Azure) 저장소를 사용할 때만 온라인 뷰어로 열 수 있습니다. 내려받아 확인하세요." />
          )
        )}
        {kind === 'NONE' && (
          <EmptyState icon={FileQuestion} title="미리보기를 지원하지 않는 형식입니다" description="내려받아 확인하세요." />
        )}
      </div>
    </Dialog>
  )
}
