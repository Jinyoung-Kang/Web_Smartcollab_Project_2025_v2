import { render, screen } from '@testing-library/react'
import { StorageMeter } from './StorageMeter'

describe('StorageMeter', () => {
  it('사용량과 한도를 보여 준다', () => {
    render(<StorageMeter storedBytes={512 * 1024} quotaBytes={1024 * 1024 * 1024} />)
    expect(screen.getByRole('meter', { name: '저장 공간 사용량' })).toHaveAttribute('aria-valuetext', '512 KB / 1.0 GB (0%), 남은 용량 1024 MB')
    expect(screen.getByText('512 KB / 1.0 GB')).toBeInTheDocument()
  })

  it('남은 용량을 함께 보여 준다 [UX-08] — 한도를 넘었으면 0', () => {
    const { unmount } = render(<StorageMeter storedBytes={512 * 1024 * 1024} quotaBytes={2 * 1024 * 1024 * 1024} />)
    expect(screen.getByText('남은 용량 1.5 GB')).toBeInTheDocument()
    expect(screen.getByRole('meter')).toHaveAttribute('aria-valuetext', '512 MB / 2.0 GB (25%), 남은 용량 1.5 GB')
    unmount()
    render(<StorageMeter storedBytes={120} quotaBytes={100} />)
    expect(screen.getByText('남은 용량 0 B')).toBeInTheDocument()
  })

  it('90% 를 넘으면 경고한다', () => {
    render(<StorageMeter storedBytes={95} quotaBytes={100} />)
    expect(screen.getByText(/공간이 거의 찼습니다/)).toBeInTheDocument()
  })
})
