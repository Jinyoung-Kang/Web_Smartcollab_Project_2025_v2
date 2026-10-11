import { lazy, Suspense, useEffect, type ReactNode } from 'react'
import { Navigate, Route, Routes, useLocation } from 'react-router'
import { useAuth } from '@/auth/AuthProvider'
import { Spinner } from '@/components/ui/misc'

// 화면마다 필요할 때 내려받습니다 (코드 분할). 로그인 화면·드라이브·앱 틀까지 나눠, 로그인하지 않은 공유 받기 화면이
// 앱 전체를 받지 않게 합니다 [L-9].
const loadDrive = () => import('@/features/drive/DrivePage')
const loadShell = () => import('@/layout/AppShell')
const LoginPage = lazy(() => import('@/auth/LoginPage').then((m) => ({ default: m.LoginPage })))
const AppShell = lazy(() => loadShell().then((m) => ({ default: m.AppShell })))
const DrivePage = lazy(() => loadDrive().then((m) => ({ default: m.DrivePage })))
const TeamRootRedirect = lazy(() => loadDrive().then((m) => ({ default: m.TeamRootRedirect })))
const TrashPage = lazy(() => import('@/features/drive/TrashPage'))
const SearchPage = lazy(() => import('@/features/drive/SearchPage'))
const EditorPage = lazy(() => import('@/features/editor/EditorPage'))
const SharePage = lazy(() => import('@/features/share/SharePage'))

function FullScreenSpinner() {
  return (
    <div className="flex h-dvh items-center justify-center">
      <Spinner />
    </div>
  )
}

function RequireAuth({ children }: { children: ReactNode }) {
  const { me, loading } = useAuth()
  const location = useLocation()
  // 로그인 확인(/api/auth/me)과 동시에 앱 틀·드라이브를 받기 시작합니다 — 확인 뒤에, 또 틀을 받은 뒤에야 드라이브를 요청하는
  // 순차 대기를 없앰. 로그인하지 않았으면 로그인 뒤에 쓰이므로 버려지지 않습니다.
  useEffect(() => {
    void loadShell()
    void loadDrive()
  }, [])
  if (loading) return <FullScreenSpinner />
  if (!me) return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />
  return <>{children}</>
}

export function App() {
  return (
    <Suspense fallback={<FullScreenSpinner />}>
      <Routes>
        <Route path="/share/:token" element={<SharePage />} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/signup" element={<LoginPage initialMode="signup" />} />
        <Route
          element={
            <RequireAuth>
              <AppShell />
            </RequireAuth>
          }
        >
          <Route index element={<Navigate to="/drive" replace />} />
          <Route path="drive" element={<DrivePage />} />
          <Route path="drive/:folderId" element={<DrivePage />} />
          <Route path="teams/:teamId" element={<TeamRootRedirect />} />
          <Route path="teams/:teamId/folders/:folderId" element={<DrivePage />} />
          <Route path="trash" element={<TrashPage />} />
          <Route path="teams/:teamId/trash" element={<TrashPage />} />
          <Route path="search" element={<SearchPage />} />
          <Route path="files/:fileId/edit" element={<EditorPage />} />
          <Route path="*" element={<Navigate to="/drive" replace />} />
        </Route>
      </Routes>
    </Suspense>
  )
}
