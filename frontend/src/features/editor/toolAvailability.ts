import type { PublicConfig } from '@/api/types'
import { modelName, type DocumentKind } from './documentKinds'

export const PRIVACY = '문서 내용이 Anthropic(Claude API)으로 전송됩니다.'

export interface ToolAvailability {
  /** 서버에 AI 키가 있어 Claude 로 요약·번역함 */
  ai: boolean
  canSummarize: boolean
  canTranslate: boolean
  summaryLabel: string
  summaryTitle: string
  translateTitle: string
}

/**
 * 문서 형식과 서버 공개 설정으로 요약·번역 단추를 켤지와 안내 문구를 정합니다 [AI-01·02]. React 에 의존하지 않는 순수 함수.
 * AI 키가 있으면 텍스트·PDF·Word 모두, 없으면 "핵심 문장"(텍스트·Word)과 DeepL 번역(텍스트, DeepL 키가 있을 때)만 됩니다.
 */
export function toolAvailability(
  kind: DocumentKind,
  config: Pick<PublicConfig, 'aiEnabled' | 'aiModel' | 'translationEnabled'> | undefined,
): ToolAvailability {
  const ai = !!config?.aiEnabled
  const model = modelName(config?.aiModel)
  const canSummarize = ai || kind !== 'PDF'
  const canTranslate = ai || (!!config?.translationEnabled && kind === 'TEXT')
  return {
    ai,
    canSummarize,
    canTranslate,
    summaryLabel: ai ? 'AI 요약' : '핵심 문장',
    summaryTitle: ai
      ? `AI(${model})로 요약합니다. ${PRIVACY}`
      : canSummarize
        ? '저장된 최신 버전에서 중요한 문장을 고릅니다 (단어 빈도, 생성형 AI 아님)'
        : 'PDF 요약은 서버에 AI API 키(ANTHROPIC_API_KEY)가 있어야 합니다',
    translateTitle: ai
      ? `AI(${model})로 번역합니다. ${PRIVACY}`
      : canTranslate
        ? 'DeepL 로 번역합니다'
        : config?.translationEnabled
          ? 'PDF·Word 번역은 서버에 AI API 키(ANTHROPIC_API_KEY)가 있어야 합니다'
          : '서버에 번역 API 키가 설정되지 않았습니다',
  }
}
