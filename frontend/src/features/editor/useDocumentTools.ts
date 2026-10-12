import { useRef, useState } from 'react'
import { fileApi } from '@/api/endpoints'
import type { TargetLanguage } from '@/api/types'
import { languageName } from './documentKinds'

export type ToolResult =
  | { kind: 'summary'; generative: boolean; summary?: string; points: string[]; total?: number; model?: string }
  | { kind: 'translation'; text: string; language: string; provider?: string; model?: string }
  | { kind: 'loading'; label: string; action: 'summary' | 'translation' }
  | { kind: 'error'; message: string }

/**
 * 문서 도구(요약·번역). 마지막에 누른 요청의 결과만 보여 줍니다 — 핵심 문장을 누르고 끝나기 전에 번역을 누르면,
 * 늦게 온 핵심 문장이 번역 결과를 덮었습니다. 패널을 닫으면 진행 중인 결과도 버립니다.
 * 다른 파일로 바뀌면(미리보기) 이전 파일의 결과는 보이지 않습니다.
 */
export function useDocumentTools(fileId: number | undefined) {
  const [state, setState] = useState<{ fileId: number; tool: ToolResult } | null>(null)
  const latest = useRef(0)

  const run = async (id: number, action: 'summary' | 'translation', loadingLabel: string, request: () => Promise<ToolResult>) => {
    const requestId = ++latest.current
    setState({ fileId: id, tool: { kind: 'loading', label: loadingLabel, action } })
    let result: ToolResult
    try {
      result = await request()
    } catch (e) {
      result = { kind: 'error', message: (e as Error).message }
    }
    if (latest.current === requestId) setState({ fileId: id, tool: result })
  }

  return {
    tool: state && state.fileId === fileId ? state.tool : null,
    close: () => {
      latest.current++
      setState(null)
    },
    summarize: (generative: boolean) => {
      if (fileId === undefined) return
      return run(fileId, 'summary', generative ? 'AI 가 요약하는 중' : '핵심 문장을 고르는 중', async () => {
        const r = await fileApi.summary(fileId)
        return {
          kind: 'summary', generative: r.method === 'generative', summary: r.summary, points: r.sentences,
          total: r.totalSentences, model: r.model,
        }
      })
    },
    translate: (target: TargetLanguage) => {
      if (fileId === undefined) return
      return run(fileId, 'translation', `${languageName(target)}로 번역하는 중`, async () => {
        const r = await fileApi.translate(fileId, target)
        return { kind: 'translation', text: r.text, language: languageName(target), provider: r.provider, model: r.model }
      })
    },
  }
}

export type DocumentTools = ReturnType<typeof useDocumentTools>
