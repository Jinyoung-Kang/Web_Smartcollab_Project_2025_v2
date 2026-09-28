import { renderHook } from '@testing-library/react'
import { useDocumentTitle } from './useDocumentTitle'

describe('useDocumentTitle [UX-02]', () => {
  it('화면 제목을 "제목 · SmartCollab" 으로 바꾸고, 화면을 떠나면 되돌린다', () => {
    document.title = 'SmartCollab'
    const { rerender, unmount } = renderHook(({ title }) => useDocumentTitle(title), { initialProps: { title: '내 드라이브' } })
    expect(document.title).toBe('내 드라이브 · SmartCollab')

    rerender({ title: '회의록' })
    expect(document.title).toBe('회의록 · SmartCollab')

    unmount()
    expect(document.title).toBe('SmartCollab')
  })

  it('제목을 아직 모르면(불러오는 중) 바꾸지 않는다', () => {
    document.title = 'SmartCollab'
    renderHook(() => useDocumentTitle(undefined))
    expect(document.title).toBe('SmartCollab')
  })
})
