import { useState } from 'react'
import { NavLink, useLocation } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { HardDrive, Plus, Trash2, Users } from 'lucide-react'
import { fileApi, teamApi } from '@/api/endpoints'
import { useTeamActivity } from '@/realtime/TeamActivity'
import { IconButton } from '@/components/ui/Button'
import { StorageMeter } from '@/components/ui/StorageMeter'
import { NewTeamDialog } from '@/features/team/NewTeamDialog'
import { formatBytes } from '@/lib/format'
import { cn } from '@/lib/cn'

const navClass = (active: boolean) =>
  cn(
    'flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors',
    active ? 'bg-brand-50 text-brand-700' : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900',
  )

export function Sidebar() {
  const location = useLocation()
  const { unread } = useTeamActivity()
  const [creating, setCreating] = useState(false)
  const teams = useQuery({ queryKey: ['teams'], queryFn: teamApi.list })
  const usage = useQuery({ queryKey: ['usage', 'personal'], queryFn: () => fileApi.usage() })

  return (
    <nav aria-label="주 메뉴" className="flex h-full flex-col overflow-y-auto px-3 py-4">
      <div className="grid gap-0.5">
        <NavLink to="/drive" className={() => navClass(location.pathname.startsWith('/drive'))}>
          <HardDrive aria-hidden className="size-4" /> 내 드라이브
        </NavLink>
        <NavLink to="/trash" end className={({ isActive }) => navClass(isActive)}>
          <Trash2 aria-hidden className="size-4" /> 휴지통
        </NavLink>
      </div>

      <div className="mt-7 mb-2 flex items-center justify-between px-3">
        <h2 className="text-xs font-semibold tracking-wide text-slate-500">팀 스토리지</h2>
        <IconButton label="새 팀 만들기" onClick={() => setCreating(true)}>
          <Plus className="size-4" />
        </IconButton>
      </div>
      <div className="grid gap-0.5">
        {teams.data?.map((team) => (
          <NavLink
            key={team.id}
            to={`/teams/${team.id}`}
            className={() => navClass(location.pathname.startsWith(`/teams/${team.id}/`) || location.pathname === `/teams/${team.id}`)}
          >
            <Users aria-hidden className="size-4 shrink-0" />
            <span className="min-w-0 flex-1 truncate">{team.name}</span>
            {unread.has(team.id) ? (
              <span className="size-2 rounded-full bg-red-500" aria-label="새 채팅 메시지" />
            ) : (
              <span className="text-xs text-slate-600">{team.memberCount}명</span>
            )}
          </NavLink>
        ))}
        {teams.data?.length === 0 && (
          <button
            onClick={() => setCreating(true)}
            className="rounded-lg border border-dashed border-slate-300 px-3 py-3 text-left text-sm text-slate-500 hover:border-brand-300 hover:text-brand-700"
          >
            첫 팀을 만들고 동료를 초대해 보세요
          </button>
        )}
      </div>

      {usage.data && (
        <div className="mt-auto rounded-xl bg-slate-50 px-3 py-3 text-xs text-slate-500">
          <p className="font-medium text-slate-700">내 드라이브 사용량</p>
          <p className="mt-1">
            파일 {usage.data.fileCount.toLocaleString()}개 · {formatBytes(usage.data.totalBytes)}
          </p>
          <StorageMeter storedBytes={usage.data.storedBytes} quotaBytes={usage.data.quotaBytes} />
          <p className="mt-0.5 text-[11px] text-slate-500">옛 버전·휴지통 포함</p>
        </div>
      )}
      <NewTeamDialog open={creating} onClose={() => setCreating(false)} />
    </nav>
  )
}
