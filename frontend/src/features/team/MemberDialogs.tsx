import { useState, type FormEvent } from 'react'
import { teamApi } from '@/api/endpoints'
import type { Member } from '@/api/types'
import { Button } from '@/components/ui/Button'
import { Dialog } from '@/components/ui/Dialog'
import { useToast } from '@/components/ui/Toast'

export function InviteDialog({ teamId, open, onClose }: { teamId: number; open: boolean; onClose: () => void }) {
  return (
    <Dialog open={open} onClose={onClose} title="멤버 초대" description="상대방의 아이디를 입력하면 알림으로 초대가 전달됩니다." size="sm">
      <InviteForm teamId={teamId} onClose={onClose} />
    </Dialog>
  )
}

function InviteForm({ teamId, onClose }: { teamId: number; onClose: () => void }) {
  const [username, setUsername] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)
  const toast = useToast()

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setPending(true)
    setError(null)
    try {
      await teamApi.invite(teamId, username.trim())
      toast.success(`${username.trim()}님에게 초대를 보냈습니다. 수락하면 팀에 합류합니다.`)
      onClose()
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setPending(false)
    }
  }

  return (
    <form onSubmit={submit} className="grid gap-4">
      <label>
        <span className="label">아이디</span>
        <input className="input" value={username} onChange={(e) => setUsername(e.target.value)} autoFocus />
      </label>
      {error && <p role="alert" className="-mt-2 text-sm text-red-600">{error}</p>}
      <div className="flex justify-end gap-2">
        <Button onClick={onClose}>취소</Button>
        <Button type="submit" variant="primary" disabled={!username.trim()} loading={pending}>초대 보내기</Button>
      </div>
    </form>
  )
}

const PERMISSIONS = [
  { key: 'canEdit', label: '편집', text: '업로드·새 폴더·이름 변경·이동·문서 수정' },
  { key: 'canDelete', label: '삭제', text: '다른 사람이 올린 파일 삭제, 폴더 삭제, 팀 휴지통 관리' },
  { key: 'canInvite', label: '초대', text: '새 멤버 초대' },
] as const

export function PermissionsDialog({ teamId, member, onClose, onSaved }: {
  teamId: number
  member: Member | null
  onClose: () => void
  onSaved: () => void
}) {
  return (
    <Dialog open={!!member} onClose={onClose} title="권한 변경"
      description={member ? `${member.name} (@${member.username})` : undefined} size="sm">
      {member && <PermissionsForm key={member.memberId} teamId={teamId} member={member} onClose={onClose} onSaved={onSaved} />}
    </Dialog>
  )
}

function PermissionsForm({ teamId, member, onClose, onSaved }: {
  teamId: number
  member: Member
  onClose: () => void
  onSaved: () => void
}) {
  const [perm, setPerm] = useState({ canEdit: member.canEdit, canDelete: member.canDelete, canInvite: member.canInvite })
  const [pending, setPending] = useState(false)
  const toast = useToast()

  const save = async () => {
    setPending(true)
    try {
      await teamApi.updatePermissions(teamId, member.memberId, perm)
      toast.success(`${member.name}님의 권한을 바꿨습니다.`)
      onSaved()
      onClose()
    } catch (e) {
      toast.error((e as Error).message)
    } finally {
      setPending(false)
    }
  }

  return (
    <div className="grid gap-2">
      {PERMISSIONS.map((p) => (
        <label key={p.key} className="flex cursor-pointer items-start gap-3 rounded-lg border border-slate-200 p-3 hover:bg-slate-50">
          <input type="checkbox" className="mt-0.5 size-4 accent-brand-600" checked={perm[p.key]}
            onChange={(e) => setPerm((s) => ({ ...s, [p.key]: e.target.checked }))} />
          <span>
            <span className="block text-sm font-medium">{p.label}</span>
            <span className="text-xs text-slate-500">{p.text}</span>
          </span>
        </label>
      ))}
      <p className="mt-1 text-xs text-slate-500">모든 멤버는 팀 파일을 볼 수 있습니다. 자신이 올린 파일은 삭제 권한이 없어도 지울 수 있습니다.</p>
      <div className="mt-3 flex justify-end gap-2">
        <Button onClick={onClose}>취소</Button>
        <Button variant="primary" onClick={save} loading={pending}>저장</Button>
      </div>
    </div>
  )
}
