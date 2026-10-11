import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { teamApi, trashApi } from '@/api/endpoints'
import { invalidateDriveChange } from '@/api/driveCache'
import { queryKeys, scopeOf } from '@/api/queryKeys'
import type { TrashItem } from '@/api/types'
import { useConfirm } from '@/components/ui/Confirm'
import { useToast } from '@/components/ui/Toast'
import { objectParticle } from '@/lib/josa'
import { purgeMessage, restoredMessage } from './trashRules'

/** 휴지통 목록과 복원·영구 삭제·비우기. 화면(TrashPage)은 그리기만 합니다 */
export function useTrash(teamId: number | undefined) {
  const qc = useQueryClient()
  const toast = useToast()
  const confirm = useConfirm()
  const team = useQuery({ queryKey: queryKeys.team(teamId), queryFn: () => teamApi.detail(teamId!), enabled: !!teamId })
  const trash = useQuery({ queryKey: queryKeys.trash.of(scopeOf(teamId)), queryFn: () => trashApi.list(teamId) })

  const refresh = () => invalidateDriveChange(qc)

  // 폴더는 안의 폴더·파일과 함께 복원됩니다. 원래 상위 폴더가 휴지통에 있으면 최상위 폴더로 갑니다 [UX-06].
  const restore = useMutation({
    mutationFn: async (item: TrashItem) =>
      item.type === 'folder' ? (await trashApi.restoreFolder(item.id)).relocated : (await trashApi.restore(item.id), false),
    onSuccess: (relocated, item) => {
      refresh()
      toast.success(restoredMessage(item.name, objectParticle(item.name), relocated))
    },
    onError: (e: Error) => toast.error(e.message),
  })
  const purge = useMutation({
    mutationFn: (item: TrashItem) => (item.type === 'folder' ? trashApi.purgeFolder(item.id) : trashApi.purge(item.id)),
    onSuccess: () => { refresh(); toast.success('영구 삭제했습니다.') },
    onError: (e: Error) => toast.error(e.message),
  })
  const empty = useMutation({
    mutationFn: () => trashApi.empty(teamId),
    onSuccess: (r) => { refresh(); toast.success(`${r.deleted}개 항목을 영구 삭제했습니다.`) },
    onError: (e: Error) => toast.error(e.message),
  })

  const askPurge = async (item: TrashItem) => {
    if (await confirm({ title: '영구 삭제할까요?', message: purgeMessage(item), confirmLabel: '영구 삭제', danger: true })) {
      purge.mutate(item)
    }
  }
  const askEmpty = async () => {
    if (await confirm({ title: '휴지통을 비울까요?', message: `${trash.data?.length ?? 0}개 항목이 영구 삭제됩니다.`, confirmLabel: '비우기', danger: true })) {
      empty.mutate()
    }
  }
  const isRestoring = (item: TrashItem) =>
    restore.isPending && restore.variables?.type === item.type && restore.variables.id === item.id

  return { team, trash, restore: restore.mutate, isRestoring, askPurge, askEmpty, emptying: empty.isPending }
}
