import { useEffect, useRef } from 'react'

/**
 * 연결이 끊겼다가 다시 이어졌을 때만 callback 을 실행합니다 (첫 연결은 제외).
 * 끊긴 동안 바뀐 서버 상태(예: 팀에서 제외됨)를 다시 불러오는 데 씁니다.
 */
export function useOnReconnect(connected: boolean, callback: () => void) {
  const everConnected = useRef(false)
  const latest = useRef(callback)
  useEffect(() => {
    latest.current = callback
  })
  useEffect(() => {
    if (!connected) return
    if (everConnected.current) latest.current()
    everConnected.current = true
  }, [connected])
}
