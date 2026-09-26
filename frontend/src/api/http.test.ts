import { ApiError, onUnauthorized, request } from './http'

function mockFetch(responses: Array<{ status: number; body?: unknown }>) {
  const calls: RequestInit[] = []
  const fn = vi.fn(async (_url: string, init?: RequestInit) => {
    calls.push(init ?? {})
    const r = responses.shift()!
    return new Response(r.body === undefined ? null : JSON.stringify(r.body), {
      status: r.status,
      headers: { 'content-type': 'application/problem+json' },
    })
  })
  vi.stubGlobal('fetch', fn)
  return { fn, calls }
}

afterEach(() => {
  vi.unstubAllGlobals()
  onUnauthorized(null)
  document.cookie = 'XSRF-TOKEN=; Max-Age=0'
})

describe('request', () => {
  it('ProblemDetail 을 ApiError(code, detail, fields) 로 바꾼다', async () => {
    mockFetch([{ status: 400, body: { code: 'INVALID_REQUEST', detail: '아이디를 입력하세요.', errors: { username: '아이디를 입력하세요.' } } }])
    const err = (await request('/api/x').catch((e: unknown) => e)) as ApiError
    expect(err).toBeInstanceOf(ApiError)
    expect(err.code).toBe('INVALID_REQUEST')
    expect(err.message).toBe('아이디를 입력하세요.')
    expect(err.fields.username).toBeDefined()
  })

  it('변경 요청에는 XSRF 쿠키 값을 헤더로 보낸다', async () => {
    document.cookie = 'XSRF-TOKEN=abc123'
    const { calls } = mockFetch([{ status: 204 }])
    await request('/api/x', { method: 'POST', json: { a: 1 } })
    expect((calls[0]!.headers as Record<string, string>)['X-XSRF-TOKEN']).toBe('abc123')
  })

  it('CSRF 토큰 오류면 토큰을 새로 받고 한 번 재시도한다', async () => {
    document.cookie = 'XSRF-TOKEN=old'
    const { fn } = mockFetch([{ status: 403, body: { code: 'CSRF_INVALID', detail: 'x' } }, { status: 200 }, { status: 204 }])
    await request('/api/x', { method: 'DELETE' })
    expect(fn).toHaveBeenCalledTimes(3)
    expect(fn.mock.calls[1]![0]).toBe('/api/auth/csrf')
  })

  it('401 이면 전역 로그아웃 처리를 호출한다 (quiet401 제외)', async () => {
    const handler = vi.fn()
    onUnauthorized(handler)
    mockFetch([{ status: 401, body: { code: 'UNAUTHORIZED' } }, { status: 401, body: { code: 'UNAUTHORIZED' } }])
    await request('/api/a').catch(() => undefined)
    await request('/api/b', { quiet401: true }).catch(() => undefined)
    expect(handler).toHaveBeenCalledTimes(1)
  })
})
