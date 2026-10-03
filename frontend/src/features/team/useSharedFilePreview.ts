import { useRef, useState } from 'react'
import { fileApi } from '@/api/endpoints'
import { ApiError } from '@/api/http'
import type { Item } from '@/api/types'
import { useToast } from '@/components/ui/Toast'

/**
 * 채팅에 공유된 파일 미리보기. 공유한 뒤 이름이 바뀌거나 지워졌을 수 있어, 메시지에 담긴 값 대신 지금 파일 정보로
 * 드라이브와 같은 미리보기를 엽니다. 여러 파일을 빠르게 누르면 마지막에 누른 파일만 엽니다.
 */
export function useSharedFilePreview() {
  const toast = useToast()
  const [preview, setPreview] = useState<Item | null>(null)
  const [opening, setOpening] = useState<number | null>(null)
  const latest = useRef<number | null>(null)

  const open = async (fileId: number) => {
    latest.current = fileId
    setOpening(fileId)
    try {
      const file = await fileApi.get(fileId)
      if (latest.current === fileId) setPreview(file)
    } catch (e) {
      toast.error(e instanceof ApiError && e.status === 404
        ? '파일을 찾을 수 없습니다. 삭제되었거나 휴지통에 있을 수 있습니다.'
        : (e as Error).message)
    } finally {
      setOpening((current) => (current === fileId ? null : current))
    }
  }

  return { preview, opening, open, close: () => setPreview(null) }
}
