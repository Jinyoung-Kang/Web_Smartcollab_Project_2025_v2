import { formatBytes } from '@/lib/format'

/**
 * 올리기 전에 거를 파일이면 그 이유를, 아니면 null 을 돌려줍니다: 업로드 한도를 넘거나 빈 파일.
 * 드라이브 업로드와 채팅 첨부가 같은 검사를 씁니다 — 이전에는 채팅 첨부가 검사 없이 한도를 넘는 파일을 끝까지 보낸 뒤
 * 서버에서 거절당했습니다.
 */
export function validateUpload(file: Pick<File, 'size'>, maxBytes: number | undefined): string | null {
  if (maxBytes && file.size > maxBytes) return `최대 ${formatBytes(maxBytes)}까지 올릴 수 있습니다.`
  if (file.size === 0) return '빈 파일은 올릴 수 없습니다.'
  return null
}
