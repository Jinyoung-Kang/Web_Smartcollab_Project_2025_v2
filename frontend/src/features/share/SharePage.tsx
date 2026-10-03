import { useState, type FormEvent } from 'react'
import { useParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { CheckCircle2, Download, KeyRound, LinkIcon } from 'lucide-react'
import { shareApi } from '@/api/endpoints'
import { ApiError } from '@/api/http'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/misc'
import { ItemIcon } from '@/lib/fileIcons'
import { formatBytes, formatDateTime } from '@/lib/format'
import { useDocumentTitle } from '@/lib/useDocumentTitle'
import { queryKeys } from '@/api/queryKeys'

/**
 * 공유 링크 페이지 (로그인 불필요).
 * 비밀번호는 URL 이 아닌 요청 본문으로 보내고, 서버가 발급한 5분짜리 서명 허가(grant)로 내려받습니다.
 * 다운로드는 브라우저가 직접 스트리밍하므로 큰 파일도 메모리에 올리지 않습니다 (v1: JS 로 전체를 받아 blob 생성).
 */
export default function SharePage() {
  const token = useParams().token ?? ''
  const info = useQuery({ queryKey: queryKeys.publicShare(token), queryFn: () => shareApi.publicInfo(token), retry: false })
  useDocumentTitle(info.data?.fileName ?? (info.isError ? '링크를 사용할 수 없음' : undefined))
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)
  const [started, setStarted] = useState(false)

  const start = (grant?: string) => {
    const a = document.createElement('a')
    a.href = shareApi.downloadUrl(token, grant)
    a.rel = 'noopener'
    document.body.appendChild(a)
    a.click()
    a.remove()
    setStarted(true)
  }

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setError(null)
    setPending(true)
    try {
      const { grant } = await shareApi.unlock(token, password)
      start(grant)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '확인하지 못했습니다.')
    } finally {
      setPending(false)
    }
  }

  const failure = info.error instanceof ApiError
    ? info.error.status === 410 ? '만료되었거나 다운로드 횟수를 모두 사용한 링크입니다.'
      : info.error.status === 404 ? '존재하지 않거나 해제된 링크입니다.' : info.error.message
    : info.error ? '링크 정보를 불러오지 못했습니다.' : null

  return (
    <div className="flex min-h-dvh flex-col items-center justify-center bg-gradient-to-b from-slate-50 to-slate-100 px-4">
      <div className="mb-6 flex items-center gap-2 font-bold text-slate-700">
        <img src="/favicon.svg" alt="" className="size-7 rounded-lg" /> SmartCollab
      </div>
      <main className="card w-full max-w-md p-7 shadow-sm">
        {info.isPending && <Spinner />}
        {failure && (
          <div className="text-center">
            <LinkIcon aria-hidden className="mx-auto size-10 text-slate-300" />
            <h1 className="mt-3 font-semibold">링크를 사용할 수 없습니다</h1>
            <p className="mt-1 text-sm text-slate-500">{failure}</p>
          </div>
        )}
        {info.data && (
          <>
            <div className="flex items-center gap-4">
              <ItemIcon type="file" name={info.data.fileName} className="size-12" />
              <div className="min-w-0">
                <h1 className="truncate font-semibold" title={info.data.fileName}>{info.data.fileName}</h1>
                <p className="text-sm text-slate-500">{formatBytes(info.data.size)}</p>
              </div>
            </div>
            <dl className="mt-5 grid grid-cols-2 gap-3 rounded-lg bg-slate-50 p-3 text-xs">
              <div>
                <dt className="text-slate-500">유효 기간</dt>
                <dd className="mt-0.5 font-medium">{info.data.expiresAt ? `${formatDateTime(info.data.expiresAt)}까지` : '제한 없음'}</dd>
              </div>
              <div>
                <dt className="text-slate-500">남은 다운로드</dt>
                <dd className="mt-0.5 font-medium">{info.data.remainingDownloads ?? '제한 없음'}{info.data.remainingDownloads !== undefined && '회'}</dd>
              </div>
            </dl>

            {started ? (
              <p className="mt-6 flex items-center gap-2 rounded-lg bg-emerald-50 px-3 py-3 text-sm text-emerald-800">
                <CheckCircle2 aria-hidden className="size-4" /> 다운로드를 시작했습니다. 브라우저의 다운로드 목록을 확인하세요.
              </p>
            ) : info.data.passwordProtected ? (
              <form onSubmit={submit} className="mt-6 grid gap-3">
                <label>
                  <span className="label flex items-center gap-1.5"><KeyRound aria-hidden className="size-4" /> 비밀번호가 걸린 파일입니다</span>
                  <input className="input" type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoFocus />
                </label>
                {error && <p role="alert" className="text-sm text-red-600">{error}</p>}
                <Button type="submit" variant="primary" loading={pending} disabled={!password}>
                  <Download className="size-4" /> 확인하고 내려받기
                </Button>
              </form>
            ) : (
              <Button variant="primary" className="mt-6 w-full" onClick={() => start()}>
                <Download className="size-4" /> 내려받기
              </Button>
            )}
          </>
        )}
      </main>
      <p className="mt-6 text-xs text-slate-500">공유받은 파일은 보낸 사람이 링크를 해제하면 더 이상 내려받을 수 없습니다.</p>
    </div>
  )
}
