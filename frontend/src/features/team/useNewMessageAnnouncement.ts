import { useState } from 'react'
import type { ChatMessage } from '@/api/types'

/**
 * 화면 읽기 프로그램에 알릴 새 메시지 — 다른 사람이 보낸 새 메시지 하나만 알립니다. 목록 전체가 aria-live 라 처음 불러올 때와
 * 이전 기록을 불러올 때마다 대화가 통째로 읽혔음 [L-8]. (렌더 중 상태 조정 — effect 로 한 번 더 그리지 않음)
 */
export function useNewMessageAnnouncement(messages: ChatMessage[], myUsername: string): string {
  const last = messages.at(-1)
  const [seenLastId, setSeenLastId] = useState(last?.id)
  const [announcement, setAnnouncement] = useState('')
  if (last?.id !== seenLastId) {
    setSeenLastId(last?.id)
    if (seenLastId !== undefined && last && last.id > seenLastId && last.sender.username !== myUsername) {
      setAnnouncement(`${last.sender.name}: ${last.type === 'FILE_SHARE' && last.file ? `파일 ${last.file.name}` : last.content}`)
    }
  }
  return announcement
}
