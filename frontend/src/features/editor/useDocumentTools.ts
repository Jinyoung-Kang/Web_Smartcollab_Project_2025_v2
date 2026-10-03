import { useState } from 'react'
import { fileApi } from '@/api/endpoints'

export type ToolResult =
  | { kind: 'summary'; sentences: string[]; total: number }
  | { kind: 'translation'; text: string; target: string }
  | { kind: 'loading'; label: string }
  | { kind: 'error'; message: string }

/** 문서 도구(핵심 문장·번역) */
export function useDocumentTools(fileId: number) {
  const [tool, setTool] = useState<ToolResult | null>(null)

  const run = async (loadingLabel: string, request: () => Promise<ToolResult>) => {
    setTool({ kind: 'loading', label: loadingLabel })
    let result: ToolResult
    try {
      result = await request()
    } catch (e) {
      result = { kind: 'error', message: (e as Error).message }
    }
    setTool(result)
  }

  return {
    tool,
    close: () => setTool(null),
    summarize: () => run('핵심 문장을 고르는 중', async () => {
      const r = await fileApi.summary(fileId)
      return { kind: 'summary', sentences: r.sentences, total: r.totalSentences }
    }),
    translate: (target: 'EN' | 'KO') => run(target === 'EN' ? '영어로 번역하는 중' : '한국어로 번역하는 중', async () => {
      const r = await fileApi.translate(fileId, target)
      return { kind: 'translation', text: r.text, target: r.targetLang }
    }),
  }
}
