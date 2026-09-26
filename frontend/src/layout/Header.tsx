import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router'
import { LogOut, Menu as MenuIcon, Search, UserX } from 'lucide-react'
import { useAuth, useMe } from '@/auth/AuthProvider'
import { useRealtime } from '@/realtime/RealtimeProvider'
import { IconButton } from '@/components/ui/Button'
import { Avatar, Menu, MenuItem } from '@/components/ui/misc'
import { NotificationBell } from '@/features/notifications/NotificationBell'
import { DeleteAccountDialog } from '@/features/account/DeleteAccountDialog'
import { cn } from '@/lib/cn'

export function Header({ onOpenMenu }: { onOpenMenu: () => void }) {
  const me = useMe()
  const { logout } = useAuth()
  const { connected } = useRealtime()
  const navigate = useNavigate()
  const location = useLocation()
  const [params] = useSearchParams()
  const [deleting, setDeleting] = useState(false)
  const teamMatch = location.pathname.match(/^\/teams\/(\d+)/)
  const scopeTeamId = teamMatch?.[1] ?? (location.pathname === '/search' ? params.get('teamId') : null)
  const [query, setQuery] = useState(location.pathname === '/search' ? (params.get('q') ?? '') : '')

  const submit = (e: FormEvent) => {
    e.preventDefault()
    const q = query.trim()
    if (!q) return
    const search = new URLSearchParams({ q })
    if (scopeTeamId) search.set('teamId', scopeTeamId)
    navigate(`/search?${search.toString()}`)
  }

  return (
    <header className="flex h-14 shrink-0 items-center gap-3 border-b border-slate-200 bg-white px-3 sm:px-4">
      <IconButton label="메뉴 열기" className="lg:hidden" onClick={onOpenMenu}>
        <MenuIcon className="size-5" />
      </IconButton>
      <Link to="/drive" className="flex items-center gap-2 font-bold text-slate-900">
        <img src="/favicon.svg" alt="" className="size-7 rounded-lg" />
        <span className="hidden sm:inline">SmartCollab</span>
      </Link>

      <form onSubmit={submit} role="search" className="mx-auto w-full max-w-xl">
        <label className="relative block">
          <span className="sr-only">파일 검색</span>
          <Search aria-hidden className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-slate-400" />
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={scopeTeamId ? '이 팀에서 파일 검색' : '내 드라이브에서 파일 검색'}
            className="input h-9 rounded-full border-slate-200 bg-slate-100 pl-9 focus:bg-white"
          />
        </label>
      </form>

      <span
        title={connected ? '실시간 연결됨' : '실시간 연결 중…'}
        className={cn('hidden items-center gap-1.5 text-xs md:inline-flex', connected ? 'text-emerald-600' : 'text-slate-400')}
      >
        <span className={cn('size-2 rounded-full', connected ? 'bg-emerald-500' : 'animate-pulse bg-slate-300')} />
        {connected ? '실시간' : '연결 중'}
      </span>
      <NotificationBell />
      <Menu
        width="w-60"
        trigger={({ toggle, open }) => (
          <button
            onClick={toggle}
            aria-expanded={open}
            aria-label="내 계정"
            className="flex items-center gap-2 rounded-full p-0.5 hover:bg-slate-100 sm:pr-3"
          >
            <Avatar name={me.name} />
            <span className="hidden text-sm font-medium sm:inline">{me.name}</span>
          </button>
        )}
      >
        {(close) => (
          <>
            <div className="border-b border-slate-100 px-3 py-2.5">
              <p className="text-sm font-semibold">{me.name}</p>
              <p className="text-xs text-slate-500">@{me.username}</p>
            </div>
            <MenuItem icon={LogOut} onClick={() => { close(); void logout() }}>
              로그아웃
            </MenuItem>
            <MenuItem icon={UserX} danger onClick={() => { close(); setDeleting(true) }}>
              회원 탈퇴
            </MenuItem>
          </>
        )}
      </Menu>
      <DeleteAccountDialog open={deleting} onClose={() => setDeleting(false)} />
    </header>
  )
}
