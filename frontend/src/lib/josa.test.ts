import { objectParticle } from './josa'

describe('objectParticle [UX-04]', () => {
  it('한글로 끝나면 받침에 따라 을/를', () => {
    expect(objectParticle('SmartCollab 데모 팀')).toBe('을')
    expect(objectParticle('회의록')).toBe('을')
    expect(objectParticle('디자인 폴더')).toBe('를')
  })
  it('한글로 끝나지 않으면(파일 확장자 등) 을(를)', () => {
    expect(objectParticle('보고서.pdf')).toBe('을(를)')
    expect(objectParticle('')).toBe('을(를)')
  })
})
