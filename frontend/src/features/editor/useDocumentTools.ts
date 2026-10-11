import { useRef, useState } from 'react'
import { fileApi } from '@/api/endpoints'
import type { TargetLanguage } from '@/api/types'

export const LANGUAGES: { code: TargetLanguage; name: string }[] = [
  { code: 'EN', name: '영어' },
  { code: 'KO', name: '한국어' },
  { code: 'JA', name: '일본어' },
  { code: 'ZH', name: '중국어(간체)' },
]

export function languageName(code: TargetLanguage): string {
  return LANGUAGES.find((l) => l.code === code)?.name ?? code
}

/** 모델 ID 를 화면에 보일 이름으로 (claude-haiku-5-5 → Claude Haiku 5.5) */
export function modelName(id?: string): string {
  const m = /^claude-([a-z]+)-(\d+)-(\d+)$/.exec(id ?? '')
  if (!m) return id ?? 'Claude'
  const [, family = '', major, minor] = m
  return `Claude ${family.charAt(0).toUpperCase()}${family.slice(1)} ${major}.${minor}`
}

/** 요약·번역할 수 있는 문서 형식 — 서버(DocumentKind)와 같은 기준 [AI-02] */
export type DocumentKind = 'TEXT' | 'PDF' | 'DOCX'

export function documentKind(file: { name: string; textEditable: boolean }): DocumentKind | null {
  if (file.textEditable) return 'TEXT'
  const ext = file.name.split('.').pop()?.toLowerCase()
  return ext === 'pdf' ? 'PDF' : ext === 'docx' ? 'DOCX' : null
}

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
