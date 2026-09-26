import { ApiError, onUnauthorized } from './http'
import { uploadFile } from './upload'

/** 요청을 보내기만 하고, 응답은 테스트가 직접 정하는 가짜 XMLHttpRequest */
class FakeXhr {
  static sent: FakeXhr[] = []
  upload: { onprogress: ((e: ProgressEvent) => void) | null } = { onprogress: null }
  status = 0
  responseText = ''
  withCredentials = false
  headers: Record<string, string> = {}
  onload: (() => void) | null = null
  onerror: (() => void) | null = null
  onabort: (() => void) | null = null
  open() {}
  setRequestHeader(name: string, value: string) {
    this.headers[name] = value
  }
  send() {
    FakeXhr.sent.push(this)
  }
  abort() {
    this.onabort?.()
  }
  respond(status: number, body: unknown) {
    this.status = status
    this.responseText = JSON.stringify(body)
    this.onload?.()
  }
}

const file = new File(['hello'], 'a.txt')
const settle = () => new Promise((r) => setTimeout(r, 0))

beforeEach(() => {
  FakeXhr.sent = []
  vi.stubGlobal('XMLHttpRequest', FakeXhr)
  document.cookie = 'XSRF-TOKEN=token-1'
})

afterEach(() => {
  vi.unstubAllGlobals()
  onUnauthorized(null)
  document.cookie = 'XSRF-TOKEN=; Max-Age=0'
})

describe('uploadFile', () => {
  it('[BUG-03] 이미 취소된 업로드는 요청을 보내지 않고 ABORTED 로 끝난다', async () => {
    const controller = new AbortController()
    controller.abort()
    const err = await uploadFile(1, file, () => {}, controller.signal).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).code).toBe('ABORTED')
    expect(FakeXhr.sent).toHaveLength(0)
  })

  it('[BUG-03] 전송 중에 취소하면 요청을 중단한다', async () => {
    const controller = new AbortController()
    const pending = uploadFile(1, file, () => {}, controller.signal).catch((e: unknown) => e)
    await settle()
    controller.abort()
    expect(((await pending) as ApiError).code).toBe('ABORTED')
  })

  it('[ARC-01] 401 이면 공통 요청과 같이 전역 로그인 만료 처리를 호출한다', async () => {
    const handler = vi.fn()
    onUnauthorized(handler)
    const pending = uploadFile(1, file, () => {}).catch((e: unknown) => e)
    await settle()
    FakeXhr.sent[0]!.respond(401, { code: 'UNAUTHORIZED', detail: '로그인이 필요합니다.' })
    expect(((await pending) as ApiError).status).toBe(401)
    expect(handler).toHaveBeenCalledOnce()
  })

  it('[ARC-01] CSRF 토큰 오류면 토큰을 새로 받아 한 번 재시도한다', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => {
      document.cookie = 'XSRF-TOKEN=token-2'
      return new Response(null, { status: 200 })
    }))
    const pending = uploadFile(1, file, () => {})
    await settle()
    FakeXhr.sent[0]!.respond(403, { code: 'CSRF_INVALID', detail: '보안 토큰이 없거나 만료되었습니다.' })
    await settle()
    expect(FakeXhr.sent).toHaveLength(2)
    expect(FakeXhr.sent[1]!.headers['X-XSRF-TOKEN']).toBe('token-2')
    FakeXhr.sent[1]!.respond(201, { type: 'file', id: 7, name: 'a.txt' })
    expect((await pending).id).toBe(7)
  })
})
