import { draftFileName } from './keepDraft'

describe('draftFileName', () => {
  it('확장자를 떼고 "(내 편집본).txt" 를 붙인다', () => {
    expect(draftFileName('회의록.md')).toBe('회의록 (내 편집본).txt')
    expect(draftFileName('report.v2.txt')).toBe('report.v2 (내 편집본).txt')
    expect(draftFileName('메모')).toBe('메모 (내 편집본).txt')
  })
})
