import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { teamApi } from '@/api/endpoints'
import { Button } from '@/components/ui/Button'
import { Dialog } from '@/components/ui/Dialog'
import { useToast } from '@/components/ui/Toast'

export function NewTeamDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [name, setName] = useState('')
  const qc = useQueryClient()
  const toast = useToast()
  const navigate = useNavigate()
  const create = useMutation({
    mutationFn: () => teamApi.create(name.trim()),
    onSuccess: (team) => {
      void qc.invalidateQueries({ queryKey: ['teams'] })
      toast.success(`'${team.name}' 팀을 만들었습니다.`)
      setName('')
      onClose()
      navigate(`/teams/${team.id}/folders/${team.rootFolderId}`)
    },
    onError: (e: Error) => toast.error(e.message),
  })

  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (name.trim()) create.mutate()
  }

  return (
    <Dialog open={open} onClose={onClose} title="새 팀 만들기" description="팀을 만들면 팀장이 되고, 동료를 초대할 수 있습니다." size="sm">
      <form onSubmit={submit} className="grid gap-4">
        <label>
          <span className="label">팀 이름</span>
          <input className="input" value={name} onChange={(e) => setName(e.target.value)} maxLength={100} autoFocus />
        </label>
        <div className="flex justify-end gap-2">
          <Button onClick={onClose}>취소</Button>
          <Button type="submit" variant="primary" disabled={!name.trim()} loading={create.isPending}>
            만들기
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
