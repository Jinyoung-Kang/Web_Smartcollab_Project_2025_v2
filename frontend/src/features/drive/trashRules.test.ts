import type { TrashItem } from '@/api/types'
import { daysLeft, expiresSoon, purgeMessage, restoredMessage } from './trashRules'

const DAY = 86_400_000
const now = Date.parse('2026-10-12T00:00:00Z')

describe('trashRules', () => {
  it('남은 날짜는 지난 날만큼 30일에서 빼고, 0 아래로 내려가지 않는다', () => {
    expect(daysLeft(new Date(now).toISOString(), now)).toBe(30)
    expect(daysLeft(new Date(now - 28 * DAY - 1).toISOString(), now)).toBe(2)
    expect(daysLeft(new Date(now - 40 * DAY).toISOString(), now)).toBe(0)
  })

  it('3일 이하만 강조한다', () => {
    expect(expiresSoon(3)).toBe(true)
    expect(expiresSoon(4)).toBe(false)
  })

  it('폴더 영구 삭제 문구는 안의 파일 수를 알린다', () => {
    const folder = { type: 'folder', id: 1, name: '보관함', fileCount: 2 } as TrashItem
    expect(purgeMessage(folder)).toBe("'보관함' 폴더와 안의 파일 2개까지 모두 삭제되며 되돌릴 수 없습니다.")
    expect(purgeMessage({ type: 'file', id: 2, name: '메모.txt' } as TrashItem)).toBe("'메모.txt'과(와) 모든 버전 기록이 삭제되며 되돌릴 수 없습니다.")
  })

  it('복원 안내는 최상위 폴더로 옮겨졌는지 알린다', () => {
    expect(restoredMessage('보관함', '을', true)).toBe("'보관함'을 최상위 폴더로 복원했습니다. 원래 상위 폴더가 휴지통에 있습니다.")
    expect(restoredMessage('메모', '를', false)).toBe("'메모'를 원래 폴더로 복원했습니다.")
  })
})
