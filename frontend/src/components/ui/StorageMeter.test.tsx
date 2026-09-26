import { render, screen } from '@testing-library/react'
import { StorageMeter } from './StorageMeter'

describe('StorageMeter', () => {
  it('사용량과 한도를 보여 준다', () => {
    render(<StorageMeter storedBytes={512 * 1024} quotaBytes={1024 * 1024 * 1024} />)
    expect(screen.getByRole('meter', { name: '저장 공간 사용량' })).toHaveAttribute('aria-valuetext', '512 KB / 1.0 GB (0%)')
    expect(screen.getByText('512 KB / 1.0 GB')).toBeInTheDocument()
  })

  it('90% 를 넘으면 경고한다', () => {
    render(<StorageMeter storedBytes={95} quotaBytes={100} />)
    expect(screen.getByText(/공간이 거의 찼습니다/)).toBeInTheDocument()
  })
})
