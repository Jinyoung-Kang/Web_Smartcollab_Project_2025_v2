import { useEffect, useRef } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { fileApi, folderApi, itemApi } from '@/api/endpoints'
import { invalidateDriveChange } from '@/api/driveCache'
import type { Item, ItemRef } from '@/api/types'
import { useConfirm } from '@/components/ui/Confirm'
import { useToast } from '@/components/ui/Toast'

/**
 * 지금 폴더에서 하는 변경(새 폴더·이름 바꾸기·이동·복사·삭제)과 그 뒤의 안내·캐시 갱신.
 * 이동·복사·삭제가 끝나면 onSelectionDone 으로 선택을 비웁니다.
 * <p>요청은 보낸 폴더의 ID 로 마무리합니다. 화면은 폴더가 바뀌어도 그대로 두므로(FB-07), 진행 중인 요청이 끝났을 때
 * 지금 보는 폴더를 갱신하거나 그 폴더의 선택을 비우면 안 됩니다(독립 검토).</p>
 */
export function useDriveMutations({ folderId, onSelectionDone }: { folderId: number; onSelectionDone: () => void }) {
  const qc = useQueryClient()
  const toast = useToast()
  const confirm = useConfirm()
  const currentFolder = useRef(folderId)
  useEffect(() => {
    currentFolder.current = folderId
  })
  const refreshFolder = (changedFolderId: number, otherFolderIds: number[] = []) =>
    invalidateDriveChange(qc, { folderIds: [changedFolderId, ...otherFolderIds] })
  /** 요청을 보낸 폴더를 아직 보고 있을 때만 선택을 비웁니다 */
  const selectionDoneIn = (requestFolderId: number) => {
    if (currentFolder.current === requestFolderId) onSelectionDone()
  }

  // 선택한 항목을 한 요청으로 지웁니다. 하나라도 지울 수 없으면 아무것도 지우지 않습니다 [PERF-03].
  const remove = useMutation({
    mutationFn: ({ targets }: { targets: Item[]; folderId: number }) => itemApi.remove(targets.map((t) => ({ type: t.type, id: t.id }))),
    onSuccess: ({ trashedFiles, trashedFolders }, { folderId: requestFolderId }) => {
      selectionDoneIn(requestFolderId)
      toast.success(trashedFolders === 0
        ? `${trashedFiles}개 파일을 휴지통으로 옮겼습니다.`
        : `${trashedFiles + trashedFolders}개 항목을 휴지통으로 옮겼습니다.`)
    },
    onError: (e: Error) => toast.error(e.message),
    onSettled: (_data, _error, { folderId: requestFolderId }) => refreshFolder(requestFolderId),
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
    if (ok) remove.mutate({ targets, folderId })
  }

  const createFolder = async (name: string) => {
    await folderApi.create(folderId, name)
    refreshFolder(folderId)
    toast.success(`'${name}' 폴더를 만들었습니다.`)
  }

  const rename = async (item: Item, name: string) => {
    const requestFolderId = folderId
    await (item.type === 'file' ? fileApi.rename(item.id, name) : folderApi.rename(item.id, name))
    refreshFolder(requestFolderId)
    selectionDoneIn(requestFolderId)
  }

  const transfer = async (mode: 'move' | 'copy', refs: ItemRef[], target: number) => {
    const requestFolderId = folderId
    if (mode === 'copy') {
      const res = await itemApi.copy(refs, target)
      toast.success(`파일 ${res.copiedFiles}개를 복사했습니다.`)
    } else {
      await itemApi.move(refs, target)
      toast.success(`${refs.length}개 항목을 옮겼습니다.`)
    }
    selectionDoneIn(requestFolderId)
    refreshFolder(requestFolderId, [target])
  }

  /** 지금 보는 폴더에서 보낸 삭제가 진행 중인지 */
  const deleting = remove.isPending && remove.variables?.folderId === folderId
  return { deleting, askDelete, createFolder, rename, transfer }
}
