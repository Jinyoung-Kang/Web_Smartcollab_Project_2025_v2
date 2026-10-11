import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { fileApi } from '@/api/endpoints'
import { queryKeys } from '@/api/queryKeys'
import { useConfirm } from '@/components/ui/Confirm'
import { useToast } from '@/components/ui/Toast'

/** 파일의 버전 기록과 되돌리기·서명. 화면(VersionHistoryDialog)은 그리기만 합니다 */
export function useVersionHistory(fileId: number | undefined) {
  const qc = useQueryClient()
  const toast = useToast()
  const confirm = useConfirm()
  const versions = useQuery({ queryKey: queryKeys.versions.of(fileId), queryFn: () => fileApi.versions(fileId!), enabled: fileId !== undefined })

  // 요청을 보낸 파일의 ID 를 변수로 넘깁니다. 닫은 뒤에 끝나도 그 파일의 캐시를 갱신합니다 [FB-11].
  const refresh = (id: number) => {
    void qc.invalidateQueries({ queryKey: queryKeys.versions.of(id) })
    void qc.invalidateQueries({ queryKey: queryKeys.fileContent(id) })
    void qc.invalidateQueries({ queryKey: queryKeys.folder.all })
  }

  const restore = useMutation({
    mutationFn: ({ fileId, versionId }: { fileId: number; versionId: number }) => fileApi.restore(fileId, versionId),
    onSuccess: (_, { fileId }) => {
      refresh(fileId)
      toast.success('선택한 버전을 현재 버전으로 되돌렸습니다.')
    },
    onError: (e: Error) => toast.error(e.message),
  })

  const sign = useMutation({
    mutationFn: (id: number) => fileApi.sign(id),
    onSuccess: (_, id) => {
      refresh(id)
      toast.success('현재 버전에 서명했습니다.')
    },
    onError: (e: Error) => toast.error(e.message),
  })

  const askRestore = async (versionId: number, when: string) => {
    const ok = await confirm({
      title: '이 버전으로 되돌릴까요?',
      message: `${when} 버전이 현재 버전이 됩니다. 이후 버전도 기록에 그대로 남으며, 기존 서명은 무효가 됩니다.`,
      confirmLabel: '되돌리기',
    })
    if (ok && fileId !== undefined) restore.mutate({ fileId, versionId })
  }

  const signCurrent = () => {
    if (fileId !== undefined) sign.mutate(fileId)
  }

  return { versions, askRestore, signCurrent, signing: sign.isPending }
}
