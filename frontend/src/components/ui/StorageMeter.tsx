import { formatBytes } from '@/lib/format'
import { cn } from '@/lib/cn'

/**
 * 저장 공간 사용량 막대. 한도는 실제 저장량(옛 버전·휴지통 포함)으로 셉니다 [SEC-05].
 * 90% 를 넘으면 경고 색으로 바꿔 미리 알립니다.
 */
export function StorageMeter({ storedBytes, quotaBytes }: { storedBytes: number; quotaBytes: number }) {
  const ratio = quotaBytes > 0 ? Math.min(storedBytes / quotaBytes, 1) : 0
  const percent = Math.round(ratio * 100)
  const warn = ratio >= 0.9
  return (
    <div>
      <div
        role="meter"
        aria-label="저장 공간 사용량"
        aria-valuemin={0}
        aria-valuemax={quotaBytes}
        aria-valuenow={storedBytes}
        aria-valuetext={`${formatBytes(storedBytes)} / ${formatBytes(quotaBytes)} (${percent}%)`}
        className="mt-2 h-1.5 overflow-hidden rounded-full bg-slate-200"
      >
        <div className={cn('h-full rounded-full', warn ? 'bg-amber-500' : 'bg-brand-500')} style={{ width: `${percent}%` }} />
      </div>
      <p className={cn('mt-1', warn && 'font-medium text-amber-700')}>
        {formatBytes(storedBytes)} / {formatBytes(quotaBytes)}
        {warn && ' · 공간이 거의 찼습니다'}
      </p>
    </div>
  )
}
