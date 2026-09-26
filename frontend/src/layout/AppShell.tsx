import { useState } from 'react'
import { Outlet, useLocation } from 'react-router'
import { RealtimeProvider } from '@/realtime/RealtimeProvider'
import { TeamActivityProvider } from '@/realtime/TeamActivity'
import { UploadProvider } from '@/features/drive/UploadProvider'
import { Header } from './Header'
import { Sidebar } from './Sidebar'
import { cn } from '@/lib/cn'
import { useMediaQuery } from '@/lib/useMediaQuery'

export function AppShell() {
  const [drawerOpen, setDrawerOpen] = useState(false)
  const location = useLocation()
  const wide = useMediaQuery('(min-width: 1024px)')

  // 화면을 이동하면 모바일 메뉴를 닫습니다 (렌더 중 상태 조정 패턴 — effect 로 한 번 더 렌더하지 않음).
  const [lastPath, setLastPath] = useState(location.pathname)
  if (lastPath !== location.pathname) {
    setLastPath(location.pathname)
    setDrawerOpen(false)
  }

  return (
    <RealtimeProvider enabled>
      <TeamActivityProvider>
        <UploadProvider>
          <div className="flex h-dvh flex-col">
            <Header onOpenMenu={() => setDrawerOpen(true)} />
            <div className="flex min-h-0 flex-1">
              {wide ? (
                <aside className="w-64 shrink-0 border-r border-slate-200 bg-white">
                  <Sidebar />
                </aside>
              ) : (
              /* 좁은 화면: 왼쪽에서 열리는 메뉴 */
              <div
                className={cn('fixed inset-0 z-40 lg:hidden', drawerOpen ? 'visible' : 'invisible')}
                aria-hidden={!drawerOpen}
              >
                <div
                  className={cn('absolute inset-0 bg-slate-900/40 transition-opacity', drawerOpen ? 'opacity-100' : 'opacity-0')}
                  onClick={() => setDrawerOpen(false)}
                />
                <aside
                  className={cn(
                    'absolute inset-y-0 left-0 w-72 max-w-[85vw] bg-white shadow-xl transition-transform',
                    drawerOpen ? 'translate-x-0' : '-translate-x-full',
                  )}
                >
                  <Sidebar />
                </aside>
              </div>
              )}
              <main className="min-w-0 flex-1 overflow-hidden">
                <Outlet />
              </main>
            </div>
          </div>
        </UploadProvider>
      </TeamActivityProvider>
    </RealtimeProvider>
  )
}
