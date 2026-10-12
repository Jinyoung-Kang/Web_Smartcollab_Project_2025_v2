import { render } from '@testing-library/react'
import { useChatScroll } from './useChatScroll'

function Chat({ loadOlder }: { loadOlder: () => void }) {
  const { scroller, onScroll } = useChatScroll({ lastId: 30, pageCount: 1, canLoadOlder: true, loadOlder })
  return <div ref={scroller} onScroll={onScroll} />
}

function Paged({ canLoadOlder, loadFailed, loadOlder }: { canLoadOlder: boolean; loadFailed: boolean; loadOlder: () => void }) {
  const { scroller, onScroll } = useChatScroll({ lastId: 30, pageCount: 1, canLoadOlder, loadOlder, loadFailed })
  return <div ref={scroller} onScroll={onScroll} />
}

/** 대화 영역의 보이는 높이·내용 높이를 정합니다(jsdom 은 배치를 계산하지 않음) */
function layout(clientHeight: number, scrollHeight: number) {
  vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(clientHeight)
  vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get').mockReturnValue(scrollHeight)
}

afterEach(() => vi.restoreAllMocks())

describe('useChatScroll', () => {
  it('[L-5] 메시지가 화면을 채우지 못해 스크롤할 수 없으면 이전 메시지를 바로 불러온다 — 위로 스크롤할 길이 없어 이전 기록을 볼 수 없었음', () => {
    layout(600, 400)
    const loadOlder = vi.fn()

    render(<Chat loadOlder={loadOlder} />)

    expect(loadOlder).toHaveBeenCalledOnce()
  })

  it('[L-5 후속] 이전 메시지를 불러오지 못했으면 다시 자동으로 부르지 않는다 — 실패할 때마다 다시 불러 요청이 끝없이 이어졌음', () => {
    layout(600, 400)
    const loadOlder = vi.fn()
    const at = (canLoadOlder: boolean, loadFailed: boolean) => <Paged canLoadOlder={canLoadOlder} loadFailed={loadFailed} loadOlder={loadOlder} />
    const view = render(at(true, false))
    view.rerender(at(false, false))   // 불러오는 중
    view.rerender(at(true, true))     // 실패 — 묶음 수는 그대로

    expect(loadOlder).toHaveBeenCalledOnce()
  })

  it('화면을 채웠거나 대화 영역이 보이지 않으면(닫힌 패널) 스크롤하기 전에는 불러오지 않는다', () => {
    const loadOlder = vi.fn()
    layout(600, 2000)
    render(<Chat loadOlder={loadOlder} />)
    vi.restoreAllMocks()
    layout(0, 0)
    render(<Chat loadOlder={loadOlder} />)

    expect(loadOlder).not.toHaveBeenCalled()
  })
})
