import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import { ApiError, onUnauthorized } from '@/api/http'
import { authApi, configApi } from '@/api/endpoints'
import type { Me, PublicConfig, UserMe } from '@/api/types'
import { queryKeys } from '@/api/queryKeys'
import { useToast } from '@/components/ui/Toast'

interface AuthState {
  me: Me | null
  loading: boolean
  setMe: (me: Me | null) => void
  logout: () => Promise<void>
  /** 로그인 상태를 끝내고 로그인 화면으로 보냅니다 (로그아웃·탈퇴·세션 만료 공통). */
  endSession: (reason?: 'logout' | 'expired') => void
  /** 세션 만료로 로그인 화면에 왔는지 (로그인 화면이 이유를 알림) [UX-03] */
  sessionExpired: boolean
  /** 로그인 화면으로 가는 중 (아직 이전 사용자 정보를 지우지 않음) */
  signingOut: boolean
  /** 로그인 화면에 도착하면 호출: 사용자 정보를 비우고 이전 사용자의 데이터를 캐시에서 지웁니다. */
  completeSignOut: () => void
}

/** 로그인과 무관해 로그아웃 뒤에도 남겨 두는 쿼리 */
const SESSION_INDEPENDENT_QUERIES = new Set(['me', 'config'])

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  const toast = useToast()
  const navigate = useNavigate()
  const [signingOut, setSigningOut] = useState(false)
  const [sessionExpired, setSessionExpired] = useState(false)

  const meQuery = useQuery({
    queryKey: queryKeys.me,
    // 로그인 전에도 200 인 /session 으로 확인해, 첫 화면마다 401 이 콘솔 오류로 찍히지 않게 합니다 [IMP-10]
    queryFn: async () => {
      const session = await authApi.session()
      return session.authenticated ? (session.user ?? null) : null
    },
    staleTime: Infinity,
    retry: false,
  })

  const setMe = (me: Me | null) => {
    queryClient.setQueryData(queryKeys.me, me)
    if (me) setSessionExpired(false)
  }

  // [BUG-09] 사용자 정보는 로그인 화면에 도착한 뒤(completeSignOut) 비웁니다.
  // - 먼저 비우면 로그인이 필요한 화면이 곧바로 사라져, 편집기의 "저장하지 않은 변경" 확인(useBlocker)을 거치지 못합니다.
  // - 로그인 화면은 signingOut 동안 이전 사용자 정보로 되돌려 보내지 않습니다.
  const endSession = useCallback((reason: 'logout' | 'expired' = 'logout') => {
    setSessionExpired(reason === 'expired')
    setSigningOut(true)
    navigate('/login', { replace: true })
  }, [navigate])

  const completeSignOut = useCallback(() => {
    // 'me' 쿼리는 지우지 않고 값만 바꿉니다. queryClient.clear() 로 지우면 이 컴포넌트가 구독하던 쿼리가 캐시에서 떨어져 나가,
    // 데이터 라우터(주소가 바뀌어도 이 컴포넌트를 다시 그리지 않음)에서는 이전 사용자 정보가 계속 남았습니다.
    queryClient.setQueryData(queryKeys.me, null)
    queryClient.removeQueries({ predicate: (query) => !SESSION_INDEPENDENT_QUERIES.has(String(query.queryKey[0])) })
    queryClient.getMutationCache().clear()
    setSigningOut(false)
  }, [queryClient])

  // 세션 만료(401)가 어느 요청에서든 감지되면 로그인 화면으로 보냅니다.
  // 로그인하지 않은 상태의 401(로그인 실패 등)은 세션 만료가 아니므로 무시합니다.
  useEffect(() => {
    onUnauthorized(() => {
      if (queryClient.getQueryData(queryKeys.me)) endSession('expired')
    })
    return () => onUnauthorized(null)
  }, [queryClient, endSession])

  /**
   * 로그아웃. 서버가 쿠키를 지워야 끝나므로, 요청이 실패하면(네트워크·5xx) 로그인 상태로 두고 알립니다 [M-3] —
   * 예전에는 실패해도 로그인 화면으로 가, HttpOnly 쿠키가 남은 공용 PC 에서 다음 사람이 새로고침하면 이어 쓸 수 있었습니다.
   * 401 은 이미 끝난 세션이라 그대로 로그인 화면으로 갑니다.
   */
  const logout = async () => {
    try {
      await authApi.logout()
    } catch (e) {
      if (!(e instanceof ApiError && e.status === 401)) {
        toast.error(`로그아웃하지 못했습니다. ${(e as Error).message}`)
        return
      }
    }
    endSession()
  }

  return (
    <AuthContext.Provider
      value={{
        me: meQuery.data ?? null, loading: meQuery.isPending, setMe, logout, endSession, sessionExpired, signingOut, completeSignOut,
      }}
    >
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}

/** 로그인이 필요한 화면에서 사용 (AppShell 아래에서만 호출 — 관리 전용 계정은 AppShell 에 오지 않음) */
export function useMe(): UserMe {
  const { me } = useAuth()
  if (!me) throw new Error('not authenticated')
  if (me.role === 'ADMIN') throw new Error('admin account has no drive')
  return me
}

export function usePublicConfig(): PublicConfig | undefined {
  return useQuery({ queryKey: queryKeys.config, queryFn: configApi.get, staleTime: Infinity }).data
}
