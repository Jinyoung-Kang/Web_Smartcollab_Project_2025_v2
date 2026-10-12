import { useEffect, useEffectEvent, useRef, useState } from 'react'

/**
 * 한 번에 그리는 행 수 [PERF-02]. 항목이 5,000개인 폴더는 전부 그리면 첫 표시 1.45초, 정렬·전체 선택이 0.2초씩 걸렸습니다(측정).
 * 앞에서부터 이만큼씩 그리고 목록 끝이 보이면 이어서 그립니다. 받은 항목을 다 그렸고 서버에 더 있으면 다음 묶음을 불러옵니다 [IMP-02].
 */
export const RENDER_BATCH = 200

/**
 * 긴 목록을 묶음 단위로 나눠 그립니다. 끝 줄(moreRef)이 화면에 들어오거나 showMore 를 부르면 다음 묶음을 그립니다.
 *
 * @param totalCount 전체 항목 수(아직 받지 않은 묶음 포함). 없으면 받은 수
 * @param hasMore    서버에 받을 묶음이 더 있음
 */
export function useProgressiveRows<T>(rows: T[], { totalCount, hasMore = false, onLoadMore }: {
  totalCount?: number
  hasMore?: boolean
  onLoadMore?: () => void
}) {
  const [renderLimit, setRenderLimit] = useState(RENDER_BATCH)
  const visible = rows.length > renderLimit ? rows.slice(0, renderLimit) : rows
  const remaining = Math.max(totalCount ?? rows.length, rows.length) - visible.length
  const showMore = () => {
    setRenderLimit((n) => n + RENDER_BATCH)
    // 다음에 그릴 만큼 받아 두지 않았으면 서버에서 다음 묶음을 불러옵니다
    if (hasMore && renderLimit + RENDER_BATCH >= rows.length) onLoadMore?.()
  }
  const moreRef = useRef<HTMLTableRowElement>(null)
  // 관찰기는 그린 수·남은 수가 바뀔 때만 다시 만들고, 호출할 때는 최신 상태(받은 수·더 있음)로 이어 그립니다
  const onEndVisible = useEffectEvent(showMore)

  // 목록 끝(더 보기 줄)이 화면에 들어오면 다음 묶음을 그립니다. 버튼도 있어 키보드·보조기기로도 이어 볼 수 있습니다.
  useEffect(() => {
    const el = moreRef.current
    if (!el || remaining === 0 || typeof IntersectionObserver === 'undefined') return
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((e) => e.isIntersecting)) onEndVisible()
    })
    observer.observe(el)
    return () => observer.disconnect()
  }, [remaining, visible.length])

  /** 처음 묶음부터 다시 그립니다(정렬을 바꿨을 때) */
  const reset = () => setRenderLimit(RENDER_BATCH)

  return { visible, remaining, showMore, reset, moreRef }
}
