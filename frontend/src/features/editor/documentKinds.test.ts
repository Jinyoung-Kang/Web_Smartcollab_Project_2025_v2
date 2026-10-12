import { documentKind, languageName, modelName } from './useDocumentTools'

describe('문서 종류·표시 이름 (리팩터링 전 동작 고정)', () => {
  it('편집할 수 있는 글은 TEXT, 확장자로 PDF·DOCX, 그 밖은 요약·번역 대상이 아니다', () => {
    expect(documentKind({ name: 'a.md', textEditable: true })).toBe('TEXT')
    expect(documentKind({ name: '보고서.PDF', textEditable: false })).toBe('PDF')
    expect(documentKind({ name: '계획.docx', textEditable: false })).toBe('DOCX')
    expect(documentKind({ name: '옛 문서.doc', textEditable: false })).toBeNull()
    expect(documentKind({ name: '확장자없음', textEditable: false })).toBeNull()
  })

  it('모델 ID 는 읽기 쉬운 이름으로, 모르는 형식은 그대로, 없으면 Claude', () => {
    expect(modelName('claude-haiku-5-5')).toBe('Claude Haiku 5.5')
    expect(modelName('custom-model')).toBe('custom-model')
    expect(modelName(undefined)).toBe('Claude')
  })

  it('번역 대상 언어 코드를 한국어 이름으로', () => {
    expect(languageName('JA')).toBe('일본어')
  })
})
