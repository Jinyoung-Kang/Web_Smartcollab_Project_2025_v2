import { lazy, Suspense, type ReactNode } from 'react'
import { Navigate, Route, Routes, useLocation } from 'react-router'
import { useAuth } from '@/auth/AuthProvider'
import { LoginPage } from '@/auth/LoginPage'
import { AppShell } from '@/layout/AppShell'
import { DrivePage, TeamRootRedirect } from '@/features/drive/DrivePage'
import { Spinner } from '@/components/ui/misc'

// 자주 쓰지 않는 화면은 필요할 때 내려받습니다 (코드 분할).
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
