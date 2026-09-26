import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { Client, type StompSubscription } from '@stomp/stompjs'

type Handler = (payload: unknown) => void

interface RealtimeApi {
  connected: boolean
  subscribe: (destination: string, handler: Handler) => () => void
  publish: (destination: string, body: unknown) => boolean
}

const RealtimeContext = createContext<RealtimeApi | null>(null)

/**
 * 로그인한 사용자당 WebSocket(STOMP) 연결 1개를 유지하고, 여러 화면의 구독을 목적지별로 묶어 관리합니다.
 * - v1 은 팀마다 SockJS 연결을 따로 열었습니다 (팀 N개 = 연결 N개).
 * - 연결이 끊기면 자동 재연결하고, 재연결 시 기존 구독을 복원합니다.
 * - 인증은 핸드셰이크 때 브라우저가 보내는 HttpOnly 쿠키로 처리됩니다.
 */
export function RealtimeProvider({ enabled, children }: { enabled: boolean; children: ReactNode }) {
  const [connected, setConnected] = useState(false)
  const clientRef = useRef<Client | null>(null)
  const handlers = useRef(new Map<string, Set<Handler>>())
  const stompSubs = useRef(new Map<string, StompSubscription>())

  const attach = useCallback((client: Client, destination: string) => {
    if (stompSubs.current.has(destination)) return
    const sub = client.subscribe(destination, (frame) => {
      let payload: unknown = frame.body
      try {
        payload = JSON.parse(frame.body)
      } catch {
        // 문자열 그대로 전달
      }
      handlers.current.get(destination)?.forEach((h) => h(payload))
    })
    stompSubs.current.set(destination, sub)
  }, [])

  useEffect(() => {
    if (!enabled) return
    const subs = stompSubs.current
    const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws'
    const client = new Client({
      brokerURL: `${scheme}://${window.location.host}/ws`,
      reconnectDelay: 3000,
      heartbeatIncoming: 20000,
      heartbeatOutgoing: 20000,
      onConnect: () => {
        subs.clear()
        handlers.current.forEach((_, destination) => attach(client, destination))
        setConnected(true)
      },
      onWebSocketClose: () => setConnected(false),
      onStompError: () => setConnected(false),
    })
    clientRef.current = client
    client.activate()
    return () => {
      clientRef.current = null
      subs.clear()
      setConnected(false)
      void client.deactivate()
    }
  }, [enabled, attach])

  const subscribe = useCallback(
    (destination: string, handler: Handler) => {
      const set = handlers.current.get(destination) ?? new Set<Handler>()
      set.add(handler)
      handlers.current.set(destination, set)
      const client = clientRef.current
      if (client?.connected) attach(client, destination)
      return () => {
        const current = handlers.current.get(destination)
        current?.delete(handler)
        if (current && current.size === 0) {
          handlers.current.delete(destination)
          stompSubs.current.get(destination)?.unsubscribe()
          stompSubs.current.delete(destination)
        }
      }
    },
    [attach],
  )

  const publish = useCallback((destination: string, body: unknown) => {
    const client = clientRef.current
    if (!client?.connected) return false
    client.publish({ destination, body: JSON.stringify(body) })
    return true
  }, [])

  const api = useMemo(() => ({ connected, subscribe, publish }), [connected, subscribe, publish])
  return <RealtimeContext.Provider value={api}>{children}</RealtimeContext.Provider>
}

export function useRealtime(): RealtimeApi {
  const ctx = useContext(RealtimeContext)
  if (!ctx) throw new Error('useRealtime must be used within RealtimeProvider')
  return ctx
}

/** 컴포넌트가 살아 있는 동안 목적지를 구독합니다. handler 는 최신 값을 참조합니다. */
export function useSubscription(destination: string | null, handler: Handler) {
  const { subscribe } = useRealtime()
  const ref = useRef(handler)
  useEffect(() => {
    ref.current = handler
  })
  useEffect(() => {
    if (!destination) return
    return subscribe(destination, (p) => ref.current(p))
  }, [destination, subscribe])
}
