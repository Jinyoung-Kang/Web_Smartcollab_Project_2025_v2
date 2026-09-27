import { useState, type FormEvent } from 'react'
import { authApi } from '@/api/endpoints'
import { ApiError } from '@/api/http'
import { useAuth } from '@/auth/AuthProvider'
import { Button } from '@/components/ui/Button'
import { Dialog } from '@/components/ui/Dialog'
import { useToast } from '@/components/ui/Toast'

/** 회원 탈퇴: 비밀번호를 다시 확인합니다 (v1 은 확인창 한 번으로 즉시 탈퇴). */
export function DeleteAccountDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)
  const { endSession } = useAuth()
  const toast = useToast()

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setPending(true)
    setError(null)
    try {
      await authApi.deleteAccount(password)
      toast.success('탈퇴가 완료되었습니다. 이용해 주셔서 감사합니다.')
      endSession()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '탈퇴하지 못했습니다.')
    } finally {
      setPending(false)
    }
  }

  return (
    <Dialog open={open} onClose={onClose} title="회원 탈퇴" size="sm">
      <form onSubmit={submit} className="grid gap-4">
        <div className="rounded-lg bg-red-50 p-3 text-sm leading-relaxed text-red-800">
          <b>내 드라이브의 모든 파일(휴지통 포함)이 영구 삭제</b>되며 되돌릴 수 없습니다. 팀에 올린 자료는 팀에 남고 작성자가
          ‘탈퇴한 사용자’로 표시됩니다. 팀장인 팀이 있으면 먼저 위임하거나 삭제해야 합니다.
        </div>
        <label>
          <span className="label">비밀번호 확인</span>
          <input className="input" type="password" autoComplete="current-password" value={password}
            onChange={(e) => setPassword(e.target.value)} autoFocus />
        </label>
        {error && <p role="alert" className="text-sm text-red-600">{error}</p>}
        <div className="flex justify-end gap-2">
          <Button onClick={onClose}>취소</Button>
          <Button type="submit" variant="danger" disabled={!password} loading={pending}>
            탈퇴하기
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
