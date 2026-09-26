import { isTeamPath } from './TeamActivity'

describe('isTeamPath', () => {
  it('그 팀의 화면만 해당한다 (/teams/1 은 /teams/12 와 다르다)', () => {
    expect(isTeamPath('/teams/1', 1)).toBe(true)
    expect(isTeamPath('/teams/1/folders/5', 1)).toBe(true)
    expect(isTeamPath('/teams/1/trash', 1)).toBe(true)
    expect(isTeamPath('/teams/12/folders/5', 1)).toBe(false)
    expect(isTeamPath('/drive/1', 1)).toBe(false)
  })
})
