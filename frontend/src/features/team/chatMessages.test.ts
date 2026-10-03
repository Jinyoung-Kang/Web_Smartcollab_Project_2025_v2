import type { ChatMessage } from '@/api/types'
import { groupChatMessages, validateChatMessage } from './chatMessages'

const msg = (id: number, username: string, createdAt: string): ChatMessage => ({
  id, type: 'CHAT', content: `메시지 ${id}`, sender: { username, name: username }, createdAt,
})

describe('validateChatMessage', () => {
  it('앞뒤 공백을 떼고, 비면 보내지 않으며, 2000자를 넘으면 이유를 알린다', () => {
    expect(validateChatMessage('  안녕  ')).toEqual({ content: '안녕' })
    expect(validateChatMessage('   ')).toBeNull()
    expect(validateChatMessage('가'.repeat(2001))).toEqual({ error: '메시지는 2000자 이하로 입력하세요.' })
    expect(validateChatMessage('가'.repeat(2000))).toEqual({ content: '가'.repeat(2000) })
  })
})

describe('groupChatMessages', () => {
  it('날짜가 바뀌면 구분선, 같은 사람이 5분 안에 이어 보내면 묶는다', () => {
    const rows = groupChatMessages([
      msg(1, 'demo1', '2026-10-01T10:00:00'),
      msg(2, 'demo1', '2026-10-01T10:04:59'),   // 같은 사람 5분 안 → 묶음
      msg(3, 'demo1', '2026-10-01T10:10:00'),   // 5분 넘음 → 새 묶음
      msg(4, 'demo2', '2026-10-01T10:10:30'),   // 다른 사람
      msg(5, 'demo2', '2026-10-02T09:00:00'),   // 다음 날 → 구분선, 묶지 않음
    ], 'demo1')
    expect(rows.map((r) => [r.newDay, r.grouped, r.mine])).toEqual([
      [true, false, true],
      [false, true, true],
      [false, false, true],
      [false, false, false],
      [true, false, false],
    ])
  })
})
