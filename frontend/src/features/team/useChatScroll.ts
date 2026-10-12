import { useEffect, useLayoutEffect, useRef } from 'react'

/**
 * 채팅 스크롤: 새 메시지가 오면 맨 아래에 있을 때만 따라 내려가고, 이전 메시지를 불러오면 보던 위치를 유지합니다.
 * 맨 위 가까이 올라가면 loadOlder 로 이전 메시지를 불러옵니다. 메시지가 화면을 채우지 못하면(스크롤할 수 없으면) 채울 때까지 이어서 불러옵니다.
 */
export function useChatScroll({ lastId, pageCount, canLoadOlder, loadOlder, visible = true }: {
  lastId: number | undefined
  pageCount: number | undefined
  canLoadOlder: boolean
  loadOlder: () => void
  /** 대화 영역이 보이는지 — 닫혀 있던 패널이 열리면 그때 화면을 채웠는지 다시 봅니다 */
  visible?: boolean
}) {
  const scroller = useRef<HTMLDivElement>(null)
  const stickToBottom = useRef(true)
  const prevHeight = useRef(0)
  const latestLoadOlder = useRef(loadOlder)
  useEffect(() => {
    latestLoadOlder.current = loadOlder
  })

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

  // 첫 묶음이 화면을 채우지 못하면 스크롤이 생기지 않아 이전 기록을 불러올 길이 없었습니다 [L-5].
  // 보이지 않는(높이 0) 닫힌 패널에서는 불러오지 않습니다.
  useEffect(() => {
    const el = scroller.current
    if (el && canLoadOlder && el.clientHeight > 0 && el.scrollHeight <= el.clientHeight) {
      prevHeight.current = el.scrollHeight
      latestLoadOlder.current()
    }
  }, [canLoadOlder, lastId, pageCount, visible])

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
