import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Crown, DoorOpen, MessageSquare, MoreVertical, Settings2, Trash2, UserMinus, UserPlus, Users, X } from 'lucide-react'
import { teamApi } from '@/api/endpoints'
import type { Member, TeamDetail } from '@/api/types'
import { useMe } from '@/auth/AuthProvider'
import { Button, IconButton } from '@/components/ui/Button'
import { useConfirm } from '@/components/ui/Confirm'
import { Avatar, Badge, Menu, MenuItem, Spinner } from '@/components/ui/misc'
import { useToast } from '@/components/ui/Toast'
import { cn } from '@/lib/cn'
import { ChatTab } from './ChatTab'
import { InviteDialog, PermissionsDialog } from './MemberDialogs'
import { useTeamActivity } from '@/realtime/TeamActivity'
import { queryKeys } from '@/api/queryKeys'
import { useTeamPresence } from './useTeamPresence'

/**
 * @param visible 화면에 보이는지. 좁은 화면에서는 닫혀 있어도 그려 두므로, 보일 때만 채팅을 "보는 중"으로 칩니다 [FB-06].
 */
export function TeamPanel({ teamId, visible = true, onClose }: { teamId: number; visible?: boolean; onClose?: () => void }) {
  const [tab, setTab] = useState<'chat' | 'members'>('chat')
  const team = useQuery({ queryKey: queryKeys.team(teamId), queryFn: () => teamApi.detail(teamId) })
  const me = useMe()
  const { unread } = useTeamActivity()
  const online = useTeamPresence(teamId, me.username)

  return (
    <div className="flex min-w-0 flex-1 flex-col">
      <div className="flex items-center gap-2 border-b border-slate-200 px-4 py-3">
        <Users aria-hidden className="size-4 text-slate-400" />
        <h2 className="min-w-0 flex-1 truncate font-semibold">{team.data?.name ?? '팀'}</h2>
        {team.data && <TeamMenu team={team.data} />}
        {onClose && (
          <IconButton label="패널 닫기" onClick={onClose}>
            <X className="size-4" />
          </IconButton>
        )}
      </div>
      <div role="tablist" className="flex border-b border-slate-200 px-2">
        {([['chat', '채팅', MessageSquare], ['members', `멤버 ${team.data?.members.length ?? ''}`, Users]] as const).map(
          ([key, label, Icon]) => (
            <button
              key={key}
              role="tab"
              aria-selected={tab === key}
              onClick={() => setTab(key)}
              className={cn(
                'relative flex items-center gap-1.5 px-3 py-2.5 text-sm font-medium',
                tab === key ? 'text-brand-700 after:absolute after:inset-x-2 after:bottom-0 after:h-0.5 after:bg-brand-600' : 'text-slate-500 hover:text-slate-800',
              )}
            >
              <Icon aria-hidden className="size-4" />
              {label}
              {key === 'chat' && tab !== 'chat' && unread.has(teamId) && <span className="size-2 rounded-full bg-red-500" />}
            </button>
          ),
        )}
        <span className="ml-auto flex items-center gap-1.5 pr-2 text-xs text-slate-500">
          <span className="size-2 rounded-full bg-emerald-500" />
          {online.size}명 접속 중
        </span>
      </div>
      {!team.data ? (
        <Spinner className="p-4" />
      ) : tab === 'chat' ? (
        <ChatTab teamId={teamId} team={team.data} active={visible} />
      ) : (
        <MembersTab team={team.data} online={online} />
      )}
    </div>
  )
}

function TeamMenu({ team }: { team: TeamDetail }) {
  const confirm = useConfirm()
  const toast = useToast()
  const navigate = useNavigate()
  const qc = useQueryClient()
  const leader = team.myPermissions.leader

  const leave = async () => {
    const ok = await confirm({ title: `'${team.name}' 팀에서 나갈까요?`, message: '다시 참여하려면 초대를 받아야 합니다.', confirmLabel: '나가기', danger: true })
    if (!ok) return
    try {
      await teamApi.leave(team.id)
      await qc.invalidateQueries({ queryKey: queryKeys.teams })
      toast.success('팀에서 나왔습니다.')
      navigate('/drive')
    } catch (e) {
      toast.error((e as Error).message)
    }
  }

  const remove = async () => {
    const ok = await confirm({
      title: '팀을 삭제할까요?',
      message: '팀 스토리지의 모든 폴더·파일과 채팅 기록이 영구 삭제되며 되돌릴 수 없습니다.',
      confirmLabel: '팀 삭제',
      danger: true,
      requireText: team.name,
    })
    if (!ok) return
    try {
      await teamApi.remove(team.id)
      await qc.invalidateQueries({ queryKey: queryKeys.teams })
      toast.success('팀을 삭제했습니다.')
      navigate('/drive')
    } catch (e) {
      toast.error((e as Error).message)
    }
  }

  return (
    <Menu trigger={({ toggle }) => (
      <IconButton label="팀 메뉴" onClick={toggle}>
        <MoreVertical className="size-4" />
      </IconButton>
    )}>
      {(close) => (
        <>
          {team.myPermissions.canDelete && (
            // 메뉴(role=menu) 안의 항목은 모두 menuitem 이어야 보조기기가 메뉴로 읽습니다 [QA-09]
            <Link to={`/teams/${team.id}/trash`} onClick={close} role="menuitem"
              className="flex items-center gap-2.5 px-3 py-2 text-sm text-slate-700 hover:bg-slate-50">
              <Trash2 aria-hidden className="size-4" /> 팀 휴지통
            </Link>
          )}
          {leader ? (
            <MenuItem icon={Trash2} danger onClick={() => { close(); void remove() }}>팀 삭제</MenuItem>
          ) : (
            <MenuItem icon={DoorOpen} danger onClick={() => { close(); void leave() }}>팀 나가기</MenuItem>
          )}
        </>
      )}
    </Menu>
  )
}

function MembersTab({ team, online }: { team: TeamDetail; online: ReadonlySet<string> }) {
  const me = useMe()
  const qc = useQueryClient()
  const toast = useToast()
  const confirm = useConfirm()
  const [inviting, setInviting] = useState(false)
  const [editing, setEditing] = useState<Member | null>(null)
  const leader = team.myPermissions.leader
  const refresh = () => {
    void qc.invalidateQueries({ queryKey: queryKeys.team(team.id) })
    void qc.invalidateQueries({ queryKey: queryKeys.teams })
  }

  const action = useMutation({
    mutationFn: async ({ kind, member }: { kind: 'remove' | 'delegate'; member: Member }) =>
      kind === 'remove' ? teamApi.removeMember(team.id, member.memberId) : teamApi.delegate(team.id, member.memberId),
    onSuccess: (_, { kind, member }) => {
      refresh()
      toast.success(kind === 'remove' ? `${member.name}님을 팀에서 내보냈습니다.` : `${member.name}님이 새 팀장이 되었습니다.`)
    },
    onError: (e: Error) => toast.error(e.message),
  })

  const ask = async (kind: 'remove' | 'delegate', member: Member) => {
    const ok = await confirm(kind === 'remove'
      ? { title: `${member.name}님을 내보낼까요?`, message: '팀 파일과 채팅에 더 이상 접근할 수 없습니다.', confirmLabel: '내보내기', danger: true }
      : { title: `${member.name}님에게 팀장을 넘길까요?`, message: '팀장 권한(권한 관리·팀 삭제)을 잃고 편집 권한만 남습니다.', confirmLabel: '팀장 넘기기' })
    if (ok) action.mutate({ kind, member })
  }

  return (
    <div className="min-h-0 flex-1 overflow-y-auto p-3">
      {team.myPermissions.canInvite && (
        <Button className="mb-3 w-full" onClick={() => setInviting(true)}>
          <UserPlus className="size-4" /> 멤버 초대
        </Button>
      )}
      <ul className="grid gap-1">
        {team.members.map((m) => (
          <li key={m.memberId} className="flex items-center gap-3 rounded-lg px-2 py-2 hover:bg-slate-50">
            <Avatar name={m.name} online={online.has(m.username)} />
            <div className="min-w-0 flex-1">
              <p className="flex items-center gap-1.5 truncate text-sm font-medium">
                {m.name}
                {m.username === me.username && <span className="text-xs text-slate-500">(나)</span>}
                {m.leader && <Crown aria-label="팀장" className="size-3.5 text-amber-500" />}
              </p>
              <div className="mt-0.5 flex flex-wrap gap-1">
                {m.leader ? <Badge tone="amber">팀장</Badge> : (
                  <>
                    {m.canEdit && <Badge>편집</Badge>}
                    {m.canDelete && <Badge>삭제</Badge>}
                    {m.canInvite && <Badge>초대</Badge>}
                    {!m.canEdit && !m.canDelete && !m.canInvite && <Badge tone="slate">보기 전용</Badge>}
                  </>
                )}
              </div>
            </div>
            {leader && !m.leader && (
              <Menu trigger={({ toggle }) => (
                <IconButton label={`${m.name} 관리`} onClick={toggle}>
                  <MoreVertical className="size-4" />
                </IconButton>
              )}>
                {(close) => (
                  <>
                    <MenuItem icon={Settings2} onClick={() => { close(); setEditing(m) }}>권한 변경</MenuItem>
                    <MenuItem icon={Crown} onClick={() => { close(); void ask('delegate', m) }}>팀장 넘기기</MenuItem>
                    <MenuItem icon={UserMinus} danger onClick={() => { close(); void ask('remove', m) }}>내보내기</MenuItem>
                  </>
                )}
              </Menu>
            )}
          </li>
        ))}
      </ul>
      <InviteDialog teamId={team.id} open={inviting} onClose={() => setInviting(false)} />
      <PermissionsDialog teamId={team.id} member={editing} onClose={() => setEditing(null)} onSaved={refresh} />
    </div>
  )
}
