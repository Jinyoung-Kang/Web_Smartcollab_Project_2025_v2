import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Copy, KeyRound, Link2, Trash2 } from 'lucide-react'
import { shareApi } from '@/api/endpoints'
import type { Item } from '@/api/types'
import { Button, IconButton } from '@/components/ui/Button'
import { Dialog } from '@/components/ui/Dialog'
import { Badge, Spinner } from '@/components/ui/misc'
import { useToast } from '@/components/ui/Toast'
import { formatDateTime } from '@/lib/format'

const EXPIRY_OPTIONS = [
  { label: '만료 없음', hours: undefined },
  { label: '1시간', hours: 1 },
  { label: '1일', hours: 24 },
  { label: '7일', hours: 24 * 7 },
  { label: '30일', hours: 24 * 30 },
]

/**
 * 공유 링크 만들기·관리. v1 은 링크를 만들기만 할 수 있었고, 만든 링크를 보거나 회수할 방법이 없었습니다.
 * 내용은 파일마다 새로 그립니다(key) — 이전에는 A 파일에 입력하다 닫은 비밀번호(가려진 칸)·기간·횟수가 B 파일의 링크에
 * 조용히 적용됐고 [FB-03], 닫은 뒤 끝난 요청이 엉뚱한 캐시 키를 갱신했습니다 [FB-11].
 */
export function ShareDialog({ file, onClose }: { file: Item | null; onClose: () => void }) {
  return (
    <Dialog open={!!file} onClose={onClose} title="공유 링크" description={file?.name} size="lg">
      {file && <ShareLinks key={file.id} fileId={file.id} />}
    </Dialog>
  )
}

function ShareLinks({ fileId }: { fileId: number }) {
  const qc = useQueryClient()
  const toast = useToast()
  const [password, setPassword] = useState('')
  const [expiry, setExpiry] = useState(3)
  const [limit, setLimit] = useState('')
  const links = useQuery({ queryKey: ['share-links', fileId], queryFn: () => shareApi.list(fileId) })

  const create = useMutation({
    mutationFn: () =>
      shareApi.create(fileId, {
        password: password || undefined,
        expiresInHours: EXPIRY_OPTIONS[expiry]?.hours,
        downloadLimit: limit ? Number(limit) : undefined,
      }),
    onSuccess: async (link) => {
      setPassword('')
      setLimit('')
      await qc.invalidateQueries({ queryKey: ['share-links', fileId] })
      await copy(link.path)
    },
    onError: (e: Error) => toast.error(e.message),
  })

  const revoke = useMutation({
    mutationFn: (id: number) => shareApi.revoke(id),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['share-links', fileId] })
      toast.success('링크를 해제했습니다. 이제 이 주소로는 내려받을 수 없습니다.')
    },
    onError: (e: Error) => toast.error(e.message),
  })

  const copy = async (path: string) => {
    const url = `${window.location.origin}${path}`
    try {
      await navigator.clipboard.writeText(url)
      toast.success('링크를 클립보드에 복사했습니다.')
    } catch {
      toast.info(url)
    }
  }

  const submit = (e: FormEvent) => {
    e.preventDefault()
    create.mutate()
  }

  return (
    <>
      <form onSubmit={submit} className="grid gap-3 rounded-xl bg-slate-50 p-4 sm:grid-cols-3">
        <label>
          <span className="label">비밀번호 (선택)</span>
          <input className="input" type="password" value={password} minLength={4} maxLength={72}
            onChange={(e) => setPassword(e.target.value)} placeholder="4자 이상" autoComplete="new-password" />
        </label>
        <label>
          <span className="label">유효 기간</span>
          <select className="input" value={expiry} onChange={(e) => setExpiry(Number(e.target.value))}>
            {EXPIRY_OPTIONS.map((o, i) => (
              <option key={o.label} value={i}>{o.label}</option>
            ))}
          </select>
        </label>
        <label>
          <span className="label">다운로드 횟수 제한</span>
          <input className="input" type="number" min={1} max={1000} value={limit}
            onChange={(e) => setLimit(e.target.value)} placeholder="제한 없음" />
        </label>
        <div className="sm:col-span-3">
          <Button type="submit" variant="primary" loading={create.isPending}
            disabled={password.length > 0 && password.length < 4}>
            <Link2 className="size-4" /> 링크 만들고 복사
          </Button>
        </div>
      </form>

      <h3 className="mt-6 mb-2 text-sm font-semibold text-slate-700">만든 링크</h3>
      {links.isPending && <Spinner />}
      {links.data?.length === 0 && <p className="text-sm text-slate-500">아직 만든 링크가 없습니다.</p>}
      <ul className="grid gap-2">
        {links.data?.map((l) => (
          <li key={l.id} className="flex items-center gap-3 rounded-lg border border-slate-200 px-3 py-2.5">
            <div className="min-w-0 flex-1">
              <div className="flex flex-wrap items-center gap-1.5">
                {l.active ? <Badge tone="green">사용 가능</Badge> : <Badge tone="red">만료됨</Badge>}
                {l.passwordProtected && (
                  <Badge tone="amber">
                    <KeyRound aria-hidden className="mr-1 size-3" />비밀번호
                  </Badge>
                )}
                <span className="text-xs text-slate-500">
                  {l.expiresAt ? `${formatDateTime(l.expiresAt)}까지` : '만료 없음'} · 다운로드 {l.downloadCount}
                  {l.downloadLimit ? `/${l.downloadLimit}` : ''}회
                </span>
              </div>
              <p className="mt-1 truncate font-mono text-xs text-slate-500">{window.location.origin}{l.path}</p>
            </div>
            <IconButton label="링크 복사" onClick={() => copy(l.path)} disabled={!l.active}>
              <Copy className="size-4" />
            </IconButton>
            <IconButton label="링크 해제" onClick={() => revoke.mutate(l.id)}>
              <Trash2 className="size-4 text-red-500" />
            </IconButton>
          </li>
        ))}
      </ul>
    </>
  )
}
