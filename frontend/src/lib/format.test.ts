import { formatBytes, formatRelative, sameDay } from './format'

describe('formatBytes', () => {
  it('단위를 자동으로 고른다 (v1: 항상 KB)', () => {
    expect(formatBytes(0)).toBe('0 B')
    expect(formatBytes(1023)).toBe('1023 B')
    expect(formatBytes(1536)).toBe('1.5 KB')
    expect(formatBytes(5 * 1024 * 1024)).toBe('5.0 MB')
    expect(formatBytes(300 * 1024 * 1024)).toBe('300 MB')
    expect(formatBytes(undefined)).toBe('—')
  })
})

describe('formatRelative', () => {
  const now = new Date('2026-09-27T12:00:00Z')
  it('가까운 시간은 상대 시간으로', () => {
    expect(formatRelative('2026-09-27T11:59:50Z', now)).toBe('방금 전')
    expect(formatRelative('2026-09-27T11:55:00Z', now)).toBe('5분 전')
    expect(formatRelative('2026-09-27T09:00:00Z', now)).toBe('3시간 전')
  })
  it('일주일이 넘으면 날짜로', () => {
    expect(formatRelative('2026-09-01T00:00:00Z', now)).toMatch(/2026/)
  })
})

describe('날짜 비교', () => {
  it('같은 날인지 비교', () => {
    expect(sameDay('2026-09-27T01:00:00', '2026-09-27T23:00:00')).toBe(true)
  })
})
