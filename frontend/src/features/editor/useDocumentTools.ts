import { useRef, useState } from 'react'
import { fileApi } from '@/api/endpoints'

export type ToolResult =
  | { kind: 'summary'; sentences: string[]; total: number }
  | { kind: 'translation'; text: string; target: string }
  | { kind: 'loading'; label: string }
  | { kind: 'error'; message: string }

/**
 * 문서 도구(핵심 문장·번역). 마지막에 누른 요청의 결과만 보여 줍니다 — 핵심 문장을 누르고 끝나기 전에 번역을 누르면,
 * 늦게 온 핵심 문장이 번역 결과를 덮었습니다. 패널을 닫으면 진행 중인 결과도 버립니다.
 */
export function useDocumentTools(fileId: number) {
  const [tool, setTool] = useState<ToolResult | null>(null)
  const latest = useRef(0)

  const run = async (loadingLabel: string, request: () => Promise<ToolResult>) => {
    const id = ++latest.current
    setTool({ kind: 'loading', label: loadingLabel })
    let result: ToolResult
    try {
      result = await request()
    } catch (e) {
      result = { kind: 'error', message: (e as Error).message }
    }
    if (latest.current === id) setTool(result)
  }

  return {
    tool,
    close: () => {
      latest.current++
      setTool(null)
    },
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
