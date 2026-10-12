import { useState } from 'react'
import { Copy, Languages, Sparkles, X } from 'lucide-react'
import type { TargetLanguage } from '@/api/types'
import { usePublicConfig } from '@/auth/AuthProvider'
import { Button, IconButton } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/misc'
import { useToast } from '@/components/ui/Toast'
import { cn } from '@/lib/cn'
import { PRIVACY, toolAvailability } from './toolAvailability'
import { LANGUAGES, modelName, type DocumentKind } from './documentKinds'
import type { DocumentTools, ToolResult } from './useDocumentTools'

/**
 * 요약·번역 단추 [AI-01·02]. 서버에 AI 키가 있으면 Claude 로 요약·번역(텍스트·PDF·Word),
 * 없으면 단어 빈도로 고르는 "핵심 문장"(텍스트·Word)과 DeepL 번역(텍스트, 키가 있을 때)만 켜집니다.
 */
export function DocumentToolButtons({ kind, tools }: { kind: DocumentKind; tools: DocumentTools }) {
  const config = usePublicConfig()
  const [language, setLanguage] = useState<TargetLanguage>('EN')
  const { ai, canSummarize, canTranslate, summaryLabel, summaryTitle, translateTitle } = toolAvailability(kind, config)
  // 진행 중인 작업의 단추는 끕니다 — 연달아 누르면 그만큼 요청·과금됐음 [M-5].
  // 다른 작업으로 바꾸는 것(요약 중 번역)은 그대로 되고, 마지막에 누른 결과만 보입니다(5단계)
  const pending = tools.tool?.kind === 'loading' ? tools.tool.action : null

  return (
    <>
      <Button size="sm" variant="ghost" disabled={!canSummarize || pending === 'summary'} onClick={() => void tools.summarize(ai)} title={summaryTitle}>
        <Sparkles className="size-4" /> {summaryLabel}
      </Button>
      <span className="inline-flex items-center gap-1">
        <select aria-label="번역 언어" value={language} onChange={(e) => setLanguage(e.target.value as TargetLanguage)}
          className="h-8 rounded-lg border border-slate-200 bg-white px-2 text-sm text-slate-700">
          {LANGUAGES.map((l) => <option key={l.code} value={l.code}>{l.name}</option>)}
        </select>
        <Button size="sm" variant="ghost" disabled={!canTranslate || pending === 'translation'} onClick={() => void tools.translate(language)} title={translateTitle}>
          <Languages className="size-4" /> 번역
        </Button>
      </span>
    </>
  )
}

/** 요약·번역 결과 패널. 생성형 결과에는 만든 모델과 전송 사실, 원문과 확인하라는 안내를 붙입니다 */
export function DocumentToolPanel({ tool, onClose, className }: { tool: ToolResult; onClose: () => void; className?: string }) {
  const toast = useToast()
  const title = tool.kind === 'summary'
    ? (tool.generative ? 'AI 요약' : '핵심 문장 (추출 요약)')
    : tool.kind === 'translation' ? `번역 결과 (${tool.language})` : '요약·번역'
  const copyText = tool.kind === 'translation' ? tool.text
    : tool.kind === 'summary' && tool.generative ? [tool.summary, ...tool.points.map((p) => `- ${p}`)].filter(Boolean).join('\n') : null
  const copy = (text: string) =>
    navigator.clipboard.writeText(text).then(() => toast.success('복사했습니다.'), () => toast.error('클립보드에 복사하지 못했습니다.'))

  return (
    <aside aria-label="요약·번역" className={cn('flex flex-col bg-slate-50', className)}>
      <div className="flex items-center gap-2 border-b border-slate-200 px-4 py-3">
        <p className="flex-1 text-sm font-semibold">{title}</p>
        {copyText && (
          <IconButton label={tool.kind === 'translation' ? '번역 결과 복사' : '요약 복사'} onClick={() => copy(copyText)}>
            <Copy className="size-4" />
          </IconButton>
        )}
        <IconButton label="닫기" onClick={onClose}>
          <X className="size-4" />
        </IconButton>
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto p-4 text-sm leading-relaxed">
        {tool.kind === 'loading' && <Spinner label={tool.label} />}
        {tool.kind === 'error' && <p className="text-red-600">{tool.message}</p>}
        {tool.kind === 'summary' && tool.generative && (
          <>
            <p className="text-slate-800">{tool.summary}</p>
            {tool.points.length > 0 && (
              <ul className="mt-3 grid list-disc gap-1.5 pl-5">
                {tool.points.map((p, i) => <li key={i}>{p}</li>)}
              </ul>
            )}
            <p className="mt-4 text-xs text-slate-600">
              AI({modelName(tool.model)})가 만든 요약이라 틀린 내용이 있을 수 있으니 원문과 확인하세요. 저장된 최신 버전을 기준으로 하며,
              요약을 위해 {PRIVACY}
            </p>
          </>
        )}
        {tool.kind === 'summary' && !tool.generative && (
          <>
            <ol className="grid list-decimal gap-2 pl-5">
              {tool.points.map((s, i) => <li key={i}>{s}</li>)}
            </ol>
            <p className="mt-4 text-xs text-slate-600">
              전체 {tool.total}문장 중 단어 빈도가 높은 문장을 원문 그대로 골랐습니다. 생성형 AI 로 새로 쓴 요약이 아니며,
              저장된 최신 버전을 기준으로 합니다.
            </p>
          </>
        )}
        {tool.kind === 'translation' && (
          <>
            <p className="whitespace-pre-wrap">{tool.text}</p>
            {tool.provider && (
              <p className="mt-4 text-xs text-slate-600">
                {tool.provider === 'claude'
                  ? `AI(${modelName(tool.model)})가 번역했습니다. 저장된 최신 버전을 기준으로 하며, 번역을 위해 ${PRIVACY}`
                  : 'DeepL 로 번역했습니다. 저장된 최신 버전을 기준으로 합니다.'}
              </p>
            )}
          </>
        )}
      </div>
    </aside>
  )
}
