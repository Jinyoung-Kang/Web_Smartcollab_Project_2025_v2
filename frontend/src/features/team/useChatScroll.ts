import { useLayoutEffect, useRef } from 'react'

/**
 * 채팅 스크롤: 새 메시지가 오면 맨 아래에 있을 때만 따라 내려가고, 이전 메시지를 불러오면 보던 위치를 유지합니다.
 * 맨 위 가까이 올라가면 loadOlder 로 이전 메시지를 불러옵니다.
 */
export function useChatScroll({ lastId, pageCount, canLoadOlder, loadOlder }: {
  lastId: number | undefined
  pageCount: number | undefined
  canLoadOlder: boolean
  loadOlder: () => void
}) {
  const scroller = useRef<HTMLDivElement>(null)
  const stickToBottom = useRef(true)
  const prevHeight = useRef(0)

  useLayoutEffect(() => {
    const el = scroller.current
    if (!el) return
    if (stickToBottom.current) {
      el.scrollTop = el.scrollHeight
    } else if (prevHeight.current && el.scrollHeight > prevHeight.current && el.scrollTop < 50) {
      el.scrollTop = el.scrollHeight - prevHeight.current
    }
    prevHeight.current = el.scrollHeight
  }, [lastId, pageCount])

  const onScroll = () => {
    const el = scroller.current
    if (!el) return
    stickToBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80
    if (el.scrollTop < 40 && canLoadOlder) {
      prevHeight.current = el.scrollHeight
      loadOlder()
    }
  }

  return { scroller, onScroll, followNewMessages: () => { stickToBottom.current = true } }
}
