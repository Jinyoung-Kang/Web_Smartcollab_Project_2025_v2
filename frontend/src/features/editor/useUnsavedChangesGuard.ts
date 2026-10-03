import { useEffect } from 'react'
import { useBlocker } from 'react-router'
import { useConfirm } from '@/components/ui/Confirm'

/**
 * 저장하지 않은 변경이 있으면 창을 닫거나 새로고침할 때(beforeunload), 앱 안의 다른 화면으로 이동할 때(useBlocker)
 * 확인합니다 [BUG-04]. 사이드바·뒤로 가기 같은 앱 안의 이동은 beforeunload 가 발생하지 않아 라우터에서 막습니다.
 */
export function useUnsavedChangesGuard(dirty: boolean) {
  const confirm = useConfirm()

  useEffect(() => {
    if (!dirty) return
    const warn = (e: BeforeUnloadEvent) => e.preventDefault()
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty])

  const blocker = useBlocker(({ currentLocation, nextLocation }) => dirty && currentLocation.pathname !== nextLocation.pathname)
  useEffect(() => {
    if (blocker.state !== 'blocked') return
    let active = true
    void confirm({
      title: '저장하지 않은 변경이 있습니다',
      message: '저장하지 않고 나가면 변경 내용이 사라집니다.',
      confirmLabel: '저장 안 하고 나가기',
      danger: true,
    }).then((leave) => {
      if (!active) return
      if (leave) blocker.proceed()
      else blocker.reset()
    })
    return () => {
      active = false
    }
  }, [blocker, confirm])
}
