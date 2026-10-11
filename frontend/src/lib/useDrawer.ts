import { useEffect, useRef, type KeyboardEvent } from 'react'

/**
 * 좁은 화면에서 옆에서 열리는 패널: 열리면 초점을 패널로 옮기고, Esc 로 닫으며, 닫히면 연 단추로 초점을 돌려줍니다 [L-8].
 * 패널 안의 대화상자·메뉴가 Esc 를 먼저 처리하면(preventDefault) 패널은 닫지 않습니다.
 */
export function useDrawer(open: boolean, onClose: () => void) {
  const panel = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const node = panel.current
    node?.focus()
    return () => {
      const now = document.activeElement
      // 패널 안에 있던 초점만 되돌립니다 — 사용자가 이미 다른 곳으로 옮겼으면 두기
      if (!now || now === document.body || node?.contains(now)) opener?.focus()
    }
  }, [open])

  const onKeyDown = (e: KeyboardEvent) => {
    if (e.key !== 'Escape' || e.defaultPrevented) return
    if (e.target instanceof Element && e.target.closest('dialog')) return
    onClose()
  }

  return { panel, panelProps: { ref: panel, tabIndex: -1, onKeyDown } }
}
