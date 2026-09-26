import { createContext, useContext, useEffect, type ReactNode } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import { ApiError, onUnauthorized } from '@/api/http'
import { authApi, configApi } from '@/api/endpoints'
import type { Me, PublicConfig } from '@/api/types'

interface AuthState {
  me: Me | null
  loading: boolean
  setMe: (me: Me | null) => void
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  const navigate = useNavigate()

  const meQuery = useQuery({
    queryKey: ['me'],
    queryFn: async () => {
      try {
        return await authApi.me()
      } catch (e) {
        if (e instanceof ApiError && e.status === 401) return null
        throw e
      }
    },
    staleTime: Infinity,
    retry: false,
  })

  const setMe = (me: Me | null) => queryClient.setQueryData(['me'], me)

  // 세션 만료(401)가 어느 요청에서든 감지되면 로그인 화면으로 보냅니다.
  useEffect(() => {
    onUnauthorized(() => {
      queryClient.clear()
      queryClient.setQueryData(['me'], null)
      navigate('/login', { replace: true })
    })
    return () => onUnauthorized(null)
  }, [queryClient, navigate])

  const logout = async () => {
    try {
      await authApi.logout()
    } finally {
      queryClient.clear()
      queryClient.setQueryData(['me'], null)
      navigate('/login', { replace: true })
    }
  }

  return (
    <AuthContext.Provider value={{ me: meQuery.data ?? null, loading: meQuery.isPending, setMe, logout }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}

/** 로그인이 필요한 화면에서 사용 (AppShell 아래에서만 호출) */
export function useMe(): Me {
  const { me } = useAuth()
  if (!me) throw new Error('not authenticated')
  return me
}

export function usePublicConfig(): PublicConfig | undefined {
  return useQuery({ queryKey: ['config'], queryFn: configApi.get, staleTime: Infinity }).data
}
