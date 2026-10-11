import { toolAvailability } from './toolAvailability'

const none = { aiEnabled: false, translationEnabled: false }
const deeplOnly = { aiEnabled: false, translationEnabled: true }
const ai = { aiEnabled: true, aiModel: 'claude-haiku-5-5', translationEnabled: true }

describe('toolAvailability', () => {
  it.each([
    // 형식, 설정, 요약, 번역
    ['TEXT', none, true, false],
    ['TEXT', deeplOnly, true, true],
    ['DOCX', deeplOnly, true, false],
    ['PDF', deeplOnly, false, false],
    ['PDF', ai, true, true],
    ['DOCX', ai, true, true],
  ] as const)('%s · %o → 요약 %s, 번역 %s', (kind, config, summarize, translate) => {
    const t = toolAvailability(kind, config)
    expect(t.canSummarize).toBe(summarize)
    expect(t.canTranslate).toBe(translate)
  })

  it('AI 가 켜지면 단추 이름이 "AI 요약"이고, 안내에 모델과 전송 사실을 적는다', () => {
    const t = toolAvailability('TEXT', ai)
    expect(t.summaryLabel).toBe('AI 요약')
    expect(t.summaryTitle).toBe('AI(Claude Haiku 5.5)로 요약합니다. 문서 내용이 Anthropic(Claude API)으로 전송됩니다.')
  })

  it('설정을 아직 받지 못했으면 AI 없이 텍스트 요약만 켠다', () => {
    const t = toolAvailability('TEXT', undefined)
    expect(t).toMatchObject({ ai: false, canSummarize: true, canTranslate: false, summaryLabel: '핵심 문장' })
    expect(t.translateTitle).toBe('서버에 번역 API 키가 설정되지 않았습니다')
  })
})
