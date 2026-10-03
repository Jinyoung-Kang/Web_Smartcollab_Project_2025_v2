import { formatBytes } from '@/lib/format'
import { validateUpload } from './validateUpload'

describe('validateUpload', () => {
  it('한도를 넘거나 빈 파일이면 이유를, 아니면 null', () => {
    expect(validateUpload({ size: 2048 }, 1024)).toBe(`최대 ${formatBytes(1024)}까지 올릴 수 있습니다.`)
    expect(validateUpload({ size: 0 }, 1024)).toBe('빈 파일은 올릴 수 없습니다.')
    expect(validateUpload({ size: 1024 }, 1024)).toBeNull()
    expect(validateUpload({ size: 10 }, undefined)).toBeNull()
  })
})
