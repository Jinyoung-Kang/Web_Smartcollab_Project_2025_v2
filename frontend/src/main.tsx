import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { createBrowserRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ApiError } from '@/api/http'
import { ToastProvider } from '@/components/ui/Toast'
import { ConfirmProvider } from '@/components/ui/Confirm'
import { AuthProvider } from '@/auth/AuthProvider'
import { AppErrorPage } from '@/layout/AppErrorPage'
import { App } from './App'
import './index.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      refetchOnWindowFocus: true,
      // 권한·존재 오류는 재시도해도 결과가 같으므로 네트워크 오류만 재시도
      retry: (count, error) => !(error instanceof ApiError && error.status > 0 && error.status < 500) && count < 2,
    },
  },
})

function Root() {
  return (
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <ConfirmProvider>
          <AuthProvider>
            <App />
          </AuthProvider>
        </ConfirmProvider>
      </ToastProvider>
    </QueryClientProvider>
  )
}

// 화면 경로는 App 의 <Routes> 가 정합니다. 데이터 라우터로 감싸는 이유는 편집기의 이동 차단(useBlocker)이
// 데이터 라우터에서만 동작하기 때문입니다 [BUG-04]. 그리다 오류가 나면 빈 화면 대신 안내 화면을 보여 줍니다 [ARC-04].
const router = createBrowserRouter([{ path: '*', element: <Root />, errorElement: <AppErrorPage /> }])

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <RouterProvider router={router} />
  </StrictMode>,
)
