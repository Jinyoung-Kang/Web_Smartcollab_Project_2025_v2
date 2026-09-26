import { useCallback, useSyncExternalStore } from 'react'

/**
 * CSS 미디어 쿼리 일치 여부. 넓은 화면/좁은 화면용 UI 를 둘 다 렌더링하고 CSS 로 숨기는 대신
 * 필요한 쪽 하나만 렌더링하기 위해 사용합니다 (중복 요청·중복 DOM 방지).
 */
export function useMediaQuery(query: string): boolean {
  const subscribe = useCallback(
    (onChange: () => void) => {
      const mql = window.matchMedia(query)
      mql.addEventListener('change', onChange)
      return () => mql.removeEventListener('change', onChange)
    },
    [query],
  )
  return useSyncExternalStore(subscribe, () => window.matchMedia(query).matches, () => false)
}
