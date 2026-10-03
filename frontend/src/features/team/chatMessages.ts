import type { ChatMessage } from '@/api/types'
import { sameDay } from '@/lib/format'

/** 서버의 채팅 메시지 길이 한도와 같습니다(ChatMessage.MAX_LENGTH) */
export const CHAT_MAX_LENGTH = 2000
/** 같은 사람이 이 시간 안에 이어 보낸 메시지는 이름·사진을 한 번만 보여 줍니다 */
const GROUP_WINDOW_MS = 5 * 60_000

/** 보낼 메시지 검사. 공백뿐이면 보내지 않고(null), 너무 길면 이유를 돌려줍니다. */
export function validateChatMessage(text: string): { content: string } | { error: string } | null {
  const content = text.trim()
  if (!content) return null
  if (content.length > CHAT_MAX_LENGTH) return { error: `메시지는 ${CHAT_MAX_LENGTH}자 이하로 입력하세요.` }
  return { content }
}

export interface ChatRow {
  message: ChatMessage
  mine: boolean
  /** 앞 메시지와 날짜가 달라 날짜 구분선을 보여 줌 */
  newDay: boolean
  /** 같은 사람이 5분 안에 이어 보낸 메시지라 이름·사진을 생략 */
  grouped: boolean
}

/** 화면에 그릴 메시지 묶음(오래된 것 → 최신 순). 렌더링과 따로 두어 테스트하고, 목록이 바뀔 때만 다시 계산합니다. */
export function groupChatMessages(messages: ChatMessage[], myUsername: string): ChatRow[] {
  return messages.map((message, i) => {
    const prev = messages[i - 1]
    const newDay = !prev || !sameDay(prev.createdAt, message.createdAt)
    const grouped = !newDay && !!prev && prev.sender.username === message.sender.username &&
      new Date(message.createdAt).getTime() - new Date(prev.createdAt).getTime() < GROUP_WINDOW_MS
    return { message, mine: message.sender.username === myUsername, newDay, grouped }
  })
}
