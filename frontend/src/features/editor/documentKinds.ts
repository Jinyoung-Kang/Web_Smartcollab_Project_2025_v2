/* 문서 종류·요약 번역 표시 이름 — React 에 의존하지 않는 순수 함수 (편집기·미리보기가 함께 씀) */
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
