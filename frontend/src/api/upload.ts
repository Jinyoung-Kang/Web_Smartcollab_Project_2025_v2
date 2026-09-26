import { ApiError, csrfHeader, ensureCsrf } from './http'
import type { Item } from './types'

/**
 * XMLHttpRequest 로 업로드해 진행률을 받습니다 (fetch 는 업로드 진행률 이벤트를 제공하지 않음).
 */
export async function uploadFile(
  folderId: number,
  file: File,
  onProgress: (ratio: number) => void,
  signal?: AbortSignal,
): Promise<Item> {
  await ensureCsrf()
  return new Promise<Item>((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', `/api/files/upload?folderId=${folderId}`)
    xhr.withCredentials = true
    Object.entries(csrfHeader()).forEach(([k, v]) => xhr.setRequestHeader(k, v))
    xhr.setRequestHeader('Accept', 'application/json')
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) onProgress(e.loaded / e.total)
    }
    xhr.onload = () => {
      const data = parseJson(xhr.responseText) as { code?: string; detail?: string } | Item | null
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve(data as Item)
      } else {
        const problem = (data ?? {}) as { code?: string; detail?: string }
        reject(new ApiError(xhr.status, problem.code ?? `HTTP_${xhr.status}`, problem.detail ?? '업로드에 실패했습니다.'))
      }
    }
    xhr.onerror = () => reject(new ApiError(0, 'NETWORK', '업로드 중 네트워크 오류가 발생했습니다.'))
    xhr.onabort = () => reject(new ApiError(0, 'ABORTED', '업로드를 취소했습니다.'))
    signal?.addEventListener('abort', () => xhr.abort())
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
