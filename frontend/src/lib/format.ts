const UNITS = ['B', 'KB', 'MB', 'GB', 'TB']

/** 1536 → "1.5 KB" (v1 은 모든 크기를 KB 로만 표시) */
export function formatBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined) return '—'
  if (bytes < 1024) return `${bytes} B`
  let value = bytes
  let unit = 0
  while (value >= 1024 && unit < UNITS.length - 1) {
    value /= 1024
    unit++
  }
  return `${value >= 100 ? Math.round(value) : value.toFixed(1)} ${UNITS[unit]}`
}

const rtf = new Intl.RelativeTimeFormat('ko', { numeric: 'auto' })
const dateFmt = new Intl.DateTimeFormat('ko', { year: 'numeric', month: 'short', day: 'numeric' })
const dateTimeFmt = new Intl.DateTimeFormat('ko', {
  year: 'numeric',
  month: 'short',
  day: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
})
const timeFmt = new Intl.DateTimeFormat('ko', { hour: 'numeric', minute: '2-digit' })

/** 방금 전 / 5분 전 / 3시간 전 / 어제 / 2026. 9. 1. */
export function formatRelative(iso: string, now: Date = new Date()): string {
  const date = new Date(iso)
  const diffSec = Math.round((date.getTime() - now.getTime()) / 1000)
  const abs = Math.abs(diffSec)
  if (abs < 45) return '방금 전'
  if (abs < 3600) return rtf.format(Math.round(diffSec / 60), 'minute')
  if (abs < 86400) return rtf.format(Math.round(diffSec / 3600), 'hour')
  if (abs < 86400 * 7) return rtf.format(Math.round(diffSec / 86400), 'day')
  return dateFmt.format(date)
}

export function formatDateTime(iso: string): string {
  return dateTimeFmt.format(new Date(iso))
}

export function formatTime(iso: string): string {
  return timeFmt.format(new Date(iso))
}

export function formatDay(iso: string): string {
  return new Intl.DateTimeFormat('ko', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'short' }).format(
    new Date(iso),
  )
}

export function sameDay(a: string, b: string): boolean {
  const x = new Date(a)
  const y = new Date(b)
  return x.getFullYear() === y.getFullYear() && x.getMonth() === y.getMonth() && x.getDate() === y.getDate()
}
