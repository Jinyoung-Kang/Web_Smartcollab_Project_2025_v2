import { useEffect } from 'react'

const APP_NAME = 'SmartCollab'

/**
 * 화면마다 브라우저 탭 제목을 바꿉니다 [UX-02]. 모든 화면이 "SmartCollab" 이면 탭·방문 기록·스크린리더에서
 * 화면을 구분할 수 없습니다 (WCAG 2.4.2 Page Titled). 제목을 아직 모르면(불러오는 중) 그대로 둡니다.
 */
export function useDocumentTitle(title: string | undefined) {
  useEffect(() => {
    if (!title) return
    const previous = document.title
    document.title = `${title} · ${APP_NAME}`
    return () => {
      document.title = previous
    }
  }, [title])
}
