import { CLIENT_ID } from './clientId'

/**
 * fetch 래퍼.
 * - 인증: HttpOnly 쿠키(SC_AUTH)는 브라우저가 자동으로 보냅니다 (credentials: same-origin).
 * - CSRF: 상태를 바꾸는 요청에 XSRF-TOKEN 쿠키 값을 X-XSRF-TOKEN 헤더로 보냅니다.
 * - 오류: 서버의 ProblemDetail(JSON)을 ApiError 로 바꿔, 화면은 code/메시지만 다룹니다.
 */

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  /** 입력 검증 실패 시 필드별 메시지 */
  readonly fields: Record<string, string>
  /** 서버 로그에서 이 요청을 찾는 추적 ID (응답 본문의 requestId) */
  readonly requestId?: string

  constructor(status: number, code: string, message: string, fields: Record<string, string> = {}, requestId?: string) {
    super(message)
    this.status = status
    this.code = code
    this.fields = fields
    this.requestId = requestId
  }
}

const CSRF_COOKIE = 'XSRF-TOKEN'
let unauthorizedHandler: (() => void) | null = null

export function onUnauthorized(handler: (() => void) | null) {
  unauthorizedHandler = handler
}

/** 세션 만료(401)를 알립니다. fetch 요청과 XHR 업로드가 같은 처리를 거치도록 공개합니다. */
export function notifyUnauthorized() {
  unauthorizedHandler?.()
}

export function readCookie(name: string): string | null {
  const match = document.cookie.split('; ').find((c) => c.startsWith(`${name}=`))
  return match ? decodeURIComponent(match.slice(name.length + 1)) : null
}

let csrfRequest: Promise<void> | null = null

export function ensureCsrf(force = false): Promise<void> {
  if (!force && readCookie(CSRF_COOKIE)) return Promise.resolve()
  csrfRequest ??= fetch('/api/auth/csrf', { credentials: 'same-origin' })
    .then(
      () => undefined,
      // 네트워크 오류면 브라우저의 영문 오류(TypeError: Failed to fetch) 대신 다른 요청과 같은 안내로 [FB-12]
      () => {
        throw new ApiError(0, 'NETWORK', FALLBACK_MESSAGES[0]!)
      },
    )
    .finally(() => {
      csrfRequest = null
    })
  return csrfRequest
}

export function csrfHeader(): Record<string, string> {
  const token = readCookie(CSRF_COOKIE)
  return token ? { 'X-XSRF-TOKEN': token } : {}
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  json?: unknown
  signal?: AbortSignal
  /** 401 이 나도 전역 로그아웃 처리를 하지 않음 (초기 로그인 확인용) */
  quiet401?: boolean
}

const FALLBACK_MESSAGES: Record<number, string> = {
  0: '서버에 연결할 수 없습니다. 네트워크를 확인하세요.',
  401: '로그인이 필요합니다.',
  403: '권한이 없습니다.',
  404: '대상을 찾을 수 없습니다.',
  413: '파일이 너무 큽니다.',
  429: '요청이 너무 많습니다. 잠시 후 다시 시도하세요.',
  500: '서버 오류가 발생했습니다.',
}

export async function request<T>(path: string, options: RequestOptions = {}, retried = false): Promise<T> {
  const method = options.method ?? 'GET'
  const headers: Record<string, string> = { Accept: 'application/json', 'X-Client-Id': CLIENT_ID }
  let body: string | undefined
  if (options.json !== undefined) {
    headers['Content-Type'] = 'application/json'
    body = JSON.stringify(options.json)
  }
  if (method !== 'GET') {
    await ensureCsrf()
    Object.assign(headers, csrfHeader())
  }

  let res: Response
  try {
    res = await fetch(path, { method, headers, body, credentials: 'same-origin', signal: options.signal })
  } catch (e) {
    if ((e as Error).name === 'AbortError') throw e
    throw new ApiError(0, 'NETWORK', FALLBACK_MESSAGES[0]!)
  }

  if (res.status === 204) return undefined as T
  const isJson = (res.headers.get('content-type') ?? '').includes('json')
  const data: unknown = isJson ? await res.json().catch(() => null) : await res.text()

  if (!res.ok) {
    const error = toApiError(res.status, isJson ? data : null)
    // CSRF 토큰이 만료·누락된 경우 한 번만 새로 받아 재시도
    if (error.code === 'CSRF_INVALID' && !retried) {
      await ensureCsrf(true)
      return request<T>(path, options, true)
    }
    if (res.status === 401 && !options.quiet401) {
      notifyUnauthorized()
    }
    throw error
  }
  return data as T
}

/** 서버의 ProblemDetail(JSON) 본문을 ApiError 로 바꿉니다. 본문이 없으면 상태 코드별 기본 문구를 씁니다. */
export function toApiError(status: number, body: unknown, fallback?: string): ApiError {
  const problem = (body && typeof body === 'object' ? body : {}) as {
    code?: string; detail?: string; errors?: Record<string, string>; requestId?: string
  }
  let message = problem.detail ?? fallback ?? FALLBACK_MESSAGES[status] ?? `요청에 실패했습니다 (${status})`
  // 서버 오류는 사용자가 고칠 수 없으므로, 문의할 때 서버 로그를 바로 찾을 수 있도록 요청 번호를 붙입니다 [UX-05].
  if (status >= 500 && problem.requestId) {
    message += ` (요청 번호 ${problem.requestId.slice(0, 8)})`
  }
  return new ApiError(status, problem.code ?? `HTTP_${status}`, message, problem.errors, problem.requestId)
}

export const http = {
  get: <T>(path: string, opts?: Omit<RequestOptions, 'method' | 'json'>) => request<T>(path, { ...opts, method: 'GET' }),
  post: <T>(path: string, json?: unknown) => request<T>(path, { method: 'POST', json }),
  put: <T>(path: string, json?: unknown) => request<T>(path, { method: 'PUT', json }),
  patch: <T>(path: string, json?: unknown) => request<T>(path, { method: 'PATCH', json }),
  // DELETE 요청에는 본문을 싣지 않습니다 — HTTP 에 정의된 의미가 없어 프록시가 버리기도 합니다 [ARC-03]
  del: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
}
