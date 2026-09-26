import { renderHook } from '@testing-library/react'
import { useOnReconnect } from './useOnReconnect'

describe('useOnReconnect', () => {
  it('첫 연결에는 실행하지 않고, 끊겼다 다시 연결될 때마다 실행한다', () => {
    const callback = vi.fn()
    const { rerender } = renderHook(({ connected }) => useOnReconnect(connected, callback), {
      initialProps: { connected: false },
    })
    rerender({ connected: true })
    expect(callback).not.toHaveBeenCalled()

    rerender({ connected: false })
    rerender({ connected: true })
    expect(callback).toHaveBeenCalledTimes(1)

    rerender({ connected: false })
    rerender({ connected: true })
    expect(callback).toHaveBeenCalledTimes(2)
  })
})
