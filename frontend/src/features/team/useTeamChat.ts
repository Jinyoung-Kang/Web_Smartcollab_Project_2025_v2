import { useMemo, useState } from 'react'
import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query'
import { chatApi } from '@/api/endpoints'
import { invalidateDriveChange } from '@/api/driveCache'
import { queryKeys } from '@/api/queryKeys'
import { uploadFile } from '@/api/upload'
import type { ChatMessage } from '@/api/types'
import { useConfirm } from '@/components/ui/Confirm'
import { useToast } from '@/components/ui/Toast'
import { useRealtime } from '@/realtime/RealtimeProvider'
import { appendChatMessage } from '@/realtime/TeamActivity'

/**
 * 팀 채팅 데이터: 최근 30개부터 불러오고 위로 스크롤하면 이전 메시지를 커서 기반으로 더 불러옵니다.
 * 보내기는 WebSocket 이 연결돼 있으면 STOMP 로, 아니면 HTTP 로 합니다(결과는 둘 다 구독자에게 방송됨).
 */
export function useTeamChat(teamId: number, rootFolderId: number) {
  const qc = useQueryClient()
  const toast = useToast()
  const confirm = useConfirm()
  const { publish } = useRealtime()
  const [uploading, setUploading] = useState(false)

  const chat = useInfiniteQuery({
    queryKey: queryKeys.chat.of(teamId),
    queryFn: ({ pageParam }) => chatApi.history(teamId, pageParam),
    initialPageParam: undefined as number | undefined,
    getNextPageParam: (last) => (last.hasMore ? last.messages[0]?.id : undefined),
    staleTime: Infinity,
  })
  // 페이지가 바뀔 때만 합칩니다(이전에는 입력할 때마다 다시 합침).
  const messages: ChatMessage[] = useMemo(
    () => (chat.data ? [...chat.data.pages].reverse().flatMap((p) => p.messages) : []),
    [chat.data],
  )

  const send = async (body: { content?: string; fileId?: number }) => {
    if (publish(`/app/teams/${teamId}/chat`, body)) return
    try {
      const msg = await chatApi.send(teamId, body)
      appendChatMessage(qc, teamId, msg)
    } catch (e) {
      toast.error((e as Error).message)
    }
  }

  /** 파일을 팀 최상위 폴더에 올리고 채팅에 공유합니다. */
  const attach = async (file: File) => {
    setUploading(true)
    try {
      const item = await uploadFile(rootFolderId, file, () => {})
      invalidateDriveChange(qc, { folderIds: [rootFolderId], scope: teamId })
      await send({ fileId: item.id })
    } catch (e) {
      toast.error((e as Error).message)
    } finally {
      setUploading(false)
    }
  }

  const clear = async () => {
    const ok = await confirm({ title: '채팅 기록을 모두 지울까요?', message: '모든 멤버의 화면에서 대화가 사라지며 되돌릴 수 없습니다.', confirmLabel: '모두 지우기', danger: true })
    if (!ok) return
    try {
      await chatApi.clear(teamId)
    } catch (e) {
      toast.error((e as Error).message)
    }
  }

  return { chat, messages, send, attach, uploading, clear }
}
