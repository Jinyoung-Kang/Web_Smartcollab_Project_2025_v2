import { CLIENT_ID } from './clientId'
import { ApiError, csrfHeader, ensureCsrf, notifyUnauthorized, toApiError } from './http'
import type { Item } from './types'

const aborted = () => new ApiError(0, 'ABORTED', '업로드를 취소했습니다.')

/**
 * XMLHttpRequest 로 업로드해 진행률을 받습니다 (fetch 는 업로드 진행률 이벤트를 제공하지 않음).
 * 오류 처리는 공통 요청(`request`)과 같습니다: CSRF 토큰 오류면 한 번 재시도, 401 이면 전역 로그인 만료 처리 [ARC-01].
 */
export async function uploadFile(
  folderId: number,
  file: File,
  onProgress: (ratio: number) => void,
  signal?: AbortSignal,
  retried = false,
): Promise<Item> {
  // 대기열에서 차례를 기다리는 동안 취소된 업로드는 보내지 않습니다 [BUG-03]
  if (signal?.aborted) throw aborted()
  await ensureCsrf()
  try {
    return await send(folderId, file, onProgress, signal)
  } catch (e) {
    if (e instanceof ApiError && e.code === 'CSRF_INVALID' && !retried) {
      await ensureCsrf(true)
      return uploadFile(folderId, file, onProgress, signal, true)
    }
    if (e instanceof ApiError && e.status === 401) notifyUnauthorized()
    throw e
  }
}

function send(folderId: number, file: File, onProgress: (ratio: number) => void, signal?: AbortSignal): Promise<Item> {
  return new Promise<Item>((resolve, reject) => {
    // abort 이벤트는 한 번만 발생하므로, 이미 취소된 신호에 리스너를 달면 영영 호출되지 않습니다.
    if (signal?.aborted) return reject(aborted())
    const xhr = new XMLHttpRequest()
    const onAbort = () => xhr.abort()
    const cleanup = () => signal?.removeEventListener('abort', onAbort)
    xhr.open('POST', `/api/files/upload?folderId=${folderId}`)
    xhr.withCredentials = true
    Object.entries(csrfHeader()).forEach(([k, v]) => xhr.setRequestHeader(k, v))
    xhr.setRequestHeader('Accept', 'application/json')
    xhr.setRequestHeader('X-Client-Id', CLIENT_ID)   // [IMP-03]
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) onProgress(e.loaded / e.total)
    }
    xhr.onload = () => {
      cleanup()
      const data = parseJson(xhr.responseText)
      if (xhr.status >= 200 && xhr.status < 300) resolve(data as Item)
      else reject(toApiError(xhr.status, data, '업로드에 실패했습니다.'))
    }
    xhr.onerror = () => {
      cleanup()
      reject(new ApiError(0, 'NETWORK', '업로드 중 네트워크 오류가 발생했습니다.'))
    }
    xhr.onabort = () => {
      cleanup()
      reject(aborted())
    }
    signal?.addEventListener('abort', onAbort, { once: true })
    const form = new FormData()
    form.append('file', file)
    xhr.send(form)
  })
}

function parseJson(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}
