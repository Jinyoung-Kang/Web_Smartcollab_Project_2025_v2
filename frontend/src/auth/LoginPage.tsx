import { useEffect, useState, type FormEvent } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router'
import { FolderLock, History, MessagesSquare, ShieldCheck, Sparkles } from 'lucide-react'
import { authApi } from '@/api/endpoints'
import { ApiError } from '@/api/http'
import { Button } from '@/components/ui/Button'
import { useAuth, usePublicConfig } from './AuthProvider'
import { cn } from '@/lib/cn'
import { useDocumentTitle } from '@/lib/useDocumentTitle'

const FEATURES = [
  { icon: FolderLock, title: '팀별 권한 관리', text: '편집·삭제·초대 권한을 멤버마다 나눠 줍니다.' },
  { icon: History, title: '버전 기록과 서명', text: '누가 언제 고쳤는지 남고, 확정본에는 서명을 남깁니다.' },
  { icon: MessagesSquare, title: '실시간 협업', text: '팀 채팅·접속 표시·폴더 변경이 바로 반영됩니다.' },
  { icon: ShieldCheck, title: '안전한 외부 공유', text: '비밀번호·만료·횟수 제한 링크로만 밖에 전달합니다.' },
]

export function LoginPage({ initialMode = 'login' }: { initialMode?: 'login' | 'signup' }) {
  const { me, setMe, signingOut, completeSignOut, sessionExpired } = useAuth()
  const config = usePublicConfig()
  const navigate = useNavigate()
  const location = useLocation()
  const [mode, setMode] = useState<'login' | 'signup'>(initialMode)
  const [form, setForm] = useState({ username: '', password: '', passwordConfirm: '', name: '', email: '' })
  const [error, setError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [submitting, setSubmitting] = useState(false)
  useDocumentTitle(mode === 'login' ? '로그인' : '회원가입')

  // 로그아웃·탈퇴·세션 만료로 왔다면 여기서 이전 사용자의 정보를 지웁니다 [BUG-09].
  useEffect(() => {
    if (signingOut) completeSignOut()
  }, [signingOut, completeSignOut])

  const from = (location.state as { from?: string } | null)?.from ?? '/drive'
  if (me && !signingOut) return <Navigate to={from} replace />

  const update = (key: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm((f) => ({ ...f, [key]: e.target.value }))

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setError(null)
    setFieldErrors({})
    if (mode === 'signup' && form.password !== form.passwordConfirm) {
      setFieldErrors({ passwordConfirm: '비밀번호가 일치하지 않습니다.' })
      return
    }
    setSubmitting(true)
    try {
      const user =
        mode === 'login'
          ? await authApi.login(form.username, form.password)
          : await authApi.signup({ ...form, email: form.email || undefined })
      setMe(user)
      navigate(from, { replace: true })
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '요청을 처리하지 못했습니다.')
      if (err instanceof ApiError) setFieldErrors(err.fields)
    } finally {
      setSubmitting(false)
    }
  }

  const switchMode = (next: 'login' | 'signup') => {
    setMode(next)
    setError(null)
    setFieldErrors({})
  }

  return (
    <main className="grid min-h-dvh lg:grid-cols-[1.1fr_1fr]">
      <section className="relative hidden overflow-hidden bg-gradient-to-br from-brand-800 via-brand-700 to-sky-500 px-14 py-12 text-white lg:flex lg:flex-col">
        <div className="flex items-center gap-2.5 text-lg font-bold">
          <img src="/favicon.svg" alt="" className="size-8 rounded-lg ring-2 ring-white/30" />
          SmartCollab
        </div>
        <div className="my-auto max-w-lg">
          <p className="mb-3 text-sm font-semibold tracking-wide text-sky-100">팀을 위한 클라우드 파일 협업 공간</p>
          <h2 className="text-4xl leading-tight font-bold">
            흩어진 파일과 대화를
            <br />한 곳에서.
          </h2>
          <ul className="mt-10 grid gap-5">
            {FEATURES.map(({ icon: Icon, title, text }) => (
              <li key={title} className="flex gap-4">
                <span className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-white/15">
                  <Icon aria-hidden className="size-5" />
                </span>
                <span>
                  <b className="block">{title}</b>
                  <span className="text-sm text-sky-50/85">{text}</span>
                </span>
              </li>
            ))}
          </ul>
        </div>
      </section>

      <section className="flex items-center justify-center px-5 py-10">
        <div className="w-full max-w-sm">
          <div className="mb-8 flex items-center gap-2.5 text-lg font-bold text-brand-700 lg:hidden">
            <img src="/favicon.svg" alt="" className="size-8 rounded-lg" />
            SmartCollab
          </div>
          <h1 className="text-2xl font-bold text-slate-900">{mode === 'login' ? '다시 오신 것을 환영해요' : '계정 만들기'}</h1>
          <p className="mt-1 text-sm text-slate-500">
            {mode === 'login' ? '아이디와 비밀번호로 로그인하세요.' : '가입하면 바로 내 드라이브를 쓸 수 있어요.'}
          </p>

          <div role="tablist" className="mt-6 grid grid-cols-2 rounded-lg bg-slate-100 p-1 text-sm font-medium">
            {(['login', 'signup'] as const).map((m) => (
              <button
                key={m}
                role="tab"
                aria-selected={mode === m}
                onClick={() => switchMode(m)}
                className={cn('rounded-md py-1.5', mode === m ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-600')}
              >
                {m === 'login' ? '로그인' : '회원가입'}
              </button>
            ))}
          </div>

          {sessionExpired && (
            <p role="status" className="mt-6 rounded-lg bg-amber-50 px-3 py-2 text-sm text-amber-800">
              로그인이 만료되었습니다. 다시 로그인해 주세요.
            </p>
          )}

          <form onSubmit={submit} className="mt-6 grid gap-4" noValidate>
            <Field label="아이디" error={fieldErrors.username}>
              <input className="input" autoComplete="username" required value={form.username} onChange={update('username')}
                placeholder={mode === 'signup' ? '영문·숫자 4~20자' : undefined} />
            </Field>
            {mode === 'signup' && (
              <>
                <Field label="이름" error={fieldErrors.name}>
                  <input className="input" autoComplete="name" required value={form.name} onChange={update('name')} />
                </Field>
                <Field label="이메일 (선택)" error={fieldErrors.email}>
                  <input className="input" type="email" autoComplete="email" value={form.email} onChange={update('email')} />
                </Field>
              </>
            )}
            <Field label="비밀번호" error={fieldErrors.password}>
              <input className="input" type="password" required value={form.password} onChange={update('password')}
                autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
                placeholder={mode === 'signup' ? '영문과 숫자를 포함해 8자 이상' : undefined} />
            </Field>
            {mode === 'signup' && (
              <Field label="비밀번호 확인" error={fieldErrors.passwordConfirm}>
                <input className="input" type="password" autoComplete="new-password" required value={form.passwordConfirm}
                  onChange={update('passwordConfirm')} />
              </Field>
            )}
            {error && (
              <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">
                {error}
              </p>
            )}
            <Button type="submit" variant="primary" loading={submitting} className="mt-1 w-full">
              {mode === 'login' ? '로그인' : '가입하고 시작하기'}
            </Button>
          </form>

          {config?.demo.enabled && mode === 'login' && (
            <div className="mt-8 rounded-xl border border-brand-100 bg-brand-50/60 p-4">
              <p className="flex items-center gap-1.5 text-sm font-semibold text-brand-800">
                <Sparkles aria-hidden className="size-4" /> 체험용 데모 계정
              </p>
              <p className="mt-1 text-xs text-slate-600">
                눌러서 입력한 뒤 로그인하세요. 여러 방문자가 함께 쓰는 계정이라 데이터는 주기적으로 초기화되고,
                탈퇴·팀 삭제처럼 다른 방문자에게 영향을 주는 기능은 막혀 있습니다.
              </p>
              <div className="mt-3 grid gap-2">
                {config.demo.accounts.map((a) => (
                  <button
                    key={a.username}
                    type="button"
                    onClick={() => setForm((f) => ({ ...f, username: a.username, password: config.demo.password ?? '' }))}
                    className="flex items-center justify-between rounded-lg bg-white px-3 py-2 text-left text-sm ring-1 ring-slate-200 hover:ring-brand-300"
                  >
                    <span>
                      <b>{a.name}</b> <span className="text-slate-500">({a.username})</span>
                    </span>
                    <span className="text-xs text-slate-500">{a.role}</span>
                  </button>
                ))}
              </div>
            </div>
          )}
        </div>
      </section>
    </main>
  )
}

function Field({ label, error, children }: { label: string; error?: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="label">{label}</span>
      {children}
      {error && <span className="mt-1 block text-xs text-red-600">{error}</span>}
    </label>
  )
}
