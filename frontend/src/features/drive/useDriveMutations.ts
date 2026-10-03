import { useMutation, useQueryClient } from '@tanstack/react-query'
import { fileApi, folderApi, itemApi } from '@/api/endpoints'
import { invalidateDriveChange } from '@/api/driveCache'
import type { Item, ItemRef } from '@/api/types'
import { useConfirm } from '@/components/ui/Confirm'
import { useToast } from '@/components/ui/Toast'

/**
 * 지금 폴더에서 하는 변경(새 폴더·이름 바꾸기·이동·복사·삭제)과 그 뒤의 안내·캐시 갱신.
 * 이동·복사·삭제가 끝나면 onSelectionDone 으로 선택을 비웁니다.
 */
export function useDriveMutations({ folderId, onSelectionDone }: { folderId: number; onSelectionDone: () => void }) {
  const qc = useQueryClient()
  const toast = useToast()
  const confirm = useConfirm()
  const refresh = (otherFolderIds: number[] = []) => invalidateDriveChange(qc, { folderIds: [folderId, ...otherFolderIds] })

  // 선택한 항목을 한 요청으로 지웁니다. 하나라도 지울 수 없으면 아무것도 지우지 않습니다 [PERF-03].
  const remove = useMutation({
    mutationFn: (targets: Item[]) => itemApi.remove(targets.map((t) => ({ type: t.type, id: t.id }))),
    onSuccess: ({ trashedFiles, trashedFolders }) => {
      onSelectionDone()
      toast.success(trashedFolders === 0
        ? `${trashedFiles}개 파일을 휴지통으로 옮겼습니다.`
        : `${trashedFiles + trashedFolders}개 항목을 휴지통으로 옮겼습니다.`)
    },
    onError: (e: Error) => toast.error(e.message),
    onSettled: () => refresh(),
  })

  const askDelete = async (targets: Item[]) => {
    if (targets.length === 0) return
    const folders = targets.filter((t) => t.type === 'folder').length
    const ok = await confirm({
      title: `${targets.length}개 항목을 삭제할까요?`,
      message: folders > 0
        ? '폴더는 안의 폴더·파일과 함께 휴지통으로 옮겨지며, 30일 안에 복원할 수 있습니다.'
        : '휴지통으로 옮겨지며 30일 안에 복원할 수 있습니다.',
      confirmLabel: '삭제',
      danger: true,
    })
    if (ok) remove.mutate(targets)
  }

  const createFolder = async (name: string) => {
    await folderApi.create(folderId, name)
    refresh()
    toast.success(`'${name}' 폴더를 만들었습니다.`)
  }

  const rename = async (item: Item, name: string) => {
    await (item.type === 'file' ? fileApi.rename(item.id, name) : folderApi.rename(item.id, name))
    refresh()
    onSelectionDone()
  }

  const transfer = async (mode: 'move' | 'copy', refs: ItemRef[], target: number) => {
    if (mode === 'copy') {
      const res = await itemApi.copy(refs, target)
      toast.success(`파일 ${res.copiedFiles}개를 복사했습니다.`)
    } else {
      await itemApi.move(refs, target)
      toast.success(`${refs.length}개 항목을 옮겼습니다.`)
    }
    onSelectionDone()
    refresh([target])
  }

  return { remove, askDelete, createFolder, rename, transfer }
}
