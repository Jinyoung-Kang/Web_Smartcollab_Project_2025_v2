import { folderPath } from './paths'

describe('folderPath', () => {
  it('팀 폴더는 팀 주소, 내 드라이브(팀 없음)는 드라이브 주소', () => {
    expect(folderPath(7, 3)).toBe('/teams/3/folders/7')
    expect(folderPath(7)).toBe('/drive/7')
    expect(folderPath(7, null)).toBe('/drive/7')
  })
})
