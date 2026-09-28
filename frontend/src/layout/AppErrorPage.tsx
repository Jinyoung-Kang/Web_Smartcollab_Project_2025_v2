import { useEffect } from 'react'
import { useRouteError } from 'react-router'
import { buttonStyles } from '@/components/ui/Button'
import { useDocumentTitle } from '@/lib/useDocumentTitle'

/** 새 배포로 이전 화면 조각(JS 파일)이 사라져 불러오지 못한 경우 (브라우저마다 문구가 다름) */
export function isChunkLoadError(error: unknown): boolean {
  return error instanceof Error
    && /dynamically imported module|Importing a module script failed/i.test(error.message)
}

/**
 * 화면을 그리다 예상하지 못한 오류가 나면 보여 주는 화면 [ARC-04].
 * 오류 경계가 없으면 React 가 화면 전체를 지워 빈 화면이 되거나(BUG-09 의 로그아웃 빈 화면), React Router 의 영문 기본 화면이
 * 나옵니다. 쿼리·인증 공급자 바깥에서 그려지므로 그것들에 기대지 않고, 이동은 새로 불러오기(전체 초기화)로 합니다.
 */
export function AppErrorPage() {
  const error = useRouteError()
  const outdated = isChunkLoadError(error)
  useDocumentTitle('오류')

  useEffect(() => {
    console.error('화면 렌더링 오류', error)
  }, [error])

  return (
    <main className="flex min-h-dvh items-center justify-center bg-slate-50 px-5">
      <div className="card w-full max-w-md p-7 text-center">
        <h1 className="text-lg font-semibold text-slate-900">
          {outdated ? '새 버전이 배포되었습니다' : '화면을 표시하지 못했습니다'}
        </h1>
        <p className="mt-2 text-sm text-slate-600">
          {outdated
            ? '새로고침하면 최신 화면을 불러옵니다.'
            : '일시적인 문제일 수 있습니다. 새로고침해도 계속되면 잠시 후 다시 시도해 주세요.'}
        </p>
        <div className="mt-6 flex justify-center gap-2">
          <button className={buttonStyles('primary')} onClick={() => window.location.reload()}>
            새로고침
          </button>
          <a className={buttonStyles('secondary')} href="/drive">
            내 드라이브로
          </a>
        </div>
      </div>
    </main>
  )
}
