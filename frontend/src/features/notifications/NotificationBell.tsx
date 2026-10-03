import { useNavigate } from 'react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Bell, BellOff, Check, Trash2, X } from 'lucide-react'
import { notificationApi, teamApi } from '@/api/endpoints'
import type { AppNotification } from '@/api/types'
import { Button, IconButton } from '@/components/ui/Button'
import { EmptyState, Menu } from '@/components/ui/misc'
import { useToast } from '@/components/ui/Toast'
import { formatRelative } from '@/lib/format'
import { cn } from '@/lib/cn'
import { queryKeys } from '@/api/queryKeys'

/**
 * 알림 목록. 새 알림은 WebSocket 개인 큐로 즉시 도착합니다 (v1: 10초마다 폴링).
 */
export function NotificationBell() {
  const qc = useQueryClient()
  const toast = useToast()
  const navigate = useNavigate()
  const { data } = useQuery({ queryKey: queryKeys.notifications, queryFn: notificationApi.list, refetchInterval: 120_000 })
  const invalidate = () => qc.invalidateQueries({ queryKey: queryKeys.notifications })
  /** 알림 요청이 실패해도 조용히 넘어가지 않고 알립니다 [FB-08] */
  const run = (request: Promise<unknown>) => request.then(invalidate, (e: Error) => toast.error(e.message))

  const respond = useMutation({
    mutationFn: ({ n, accept }: { n: AppNotification; accept: boolean }) =>
      accept ? teamApi.acceptInvitation(n.invitationId!) : teamApi.rejectInvitation(n.invitationId!),
    onSuccess: (_, { n, accept }) => {
      void invalidate()
      void qc.invalidateQueries({ queryKey: queryKeys.teams })
      void run(notificationApi.read(n.id))
      toast.success(accept ? '팀에 참여했습니다.' : '초대를 거절했습니다.')
      if (accept && n.teamId) navigate(`/teams/${n.teamId}`)
    },
    onError: (e: Error) => toast.error(e.message),
  })

  const unread = data?.unreadCount ?? 0

  return (
    <Menu
      role="dialog"
      label="알림"
      width="w-[22rem] max-w-[calc(100vw-1.5rem)]"
      trigger={({ toggle, open }) => (
        <button
          onClick={toggle}
          aria-expanded={open}
          aria-label={`알림 ${unread}개 읽지 않음`}
          className="relative rounded-full p-2 text-slate-600 hover:bg-slate-100"
        >
          <Bell className="size-5" />
          {unread > 0 && (
            <span className="absolute top-1 right-1 flex min-w-4 items-center justify-center rounded-full bg-red-500 px-1 text-[10px] font-bold text-white">
              {unread > 9 ? '9+' : unread}
            </span>
          )}
        </button>
      )}
    >
      {(close) => (
        <div>
          <div className="flex items-center justify-between border-b border-slate-100 px-3 py-2">
            <span className="text-sm font-semibold">알림</span>
            <div className="flex gap-1">
              <IconButton label="모두 읽음" disabled={unread === 0}
                onClick={() => void run(notificationApi.readAll())}>
                <Check className="size-4" />
              </IconButton>
              <IconButton label="모두 삭제" disabled={!data?.items.length}
                onClick={() => void run(notificationApi.removeAll())}>
                <Trash2 className="size-4" />
              </IconButton>
            </div>
          </div>
          <ul className="max-h-96 overflow-y-auto">
            {data?.items.length === 0 && <EmptyState icon={BellOff} title="새 알림이 없습니다" className="py-10" />}
            {data?.items.map((n) => {
              const pendingInvite = n.type === 'TEAM_INVITE' && n.invitationId && n.invitationStatus === 'PENDING'
              return (
                <li key={n.id} className={cn('group flex gap-3 border-b border-slate-50 px-3 py-3', !n.read && 'bg-brand-50/50')}>
                  <span className={cn('mt-1.5 size-2 shrink-0 rounded-full', n.read ? 'bg-transparent' : 'bg-brand-500')} />
                  <div className="min-w-0 flex-1">
                    <p className="text-sm leading-snug text-slate-700">{n.content}</p>
                    <p className="mt-1 text-xs text-slate-500">{formatRelative(n.createdAt)}</p>
                    {pendingInvite && (
                      <div className="mt-2 flex gap-2">
                        <Button size="sm" variant="primary" loading={respond.isPending}
                          onClick={() => respond.mutate({ n, accept: true }, { onSuccess: close })}>수락</Button>
                        <Button size="sm" onClick={() => respond.mutate({ n, accept: false })}>거절</Button>
                      </div>
                    )}
                    {n.type === 'TEAM_INVITE' && n.invitationStatus && n.invitationStatus !== 'PENDING' && (
                      <p className="mt-1 text-xs text-slate-500">
                        {n.invitationStatus === 'ACCEPTED' ? '수락한 초대입니다.' : '거절한 초대입니다.'}
                      </p>
                    )}
                  </div>
                  <div className="flex flex-col gap-1 opacity-0 transition-opacity group-hover:opacity-100 focus-within:opacity-100">
                    {!n.read && (
                      <IconButton label="읽음 표시" onClick={() => void run(notificationApi.read(n.id))}>
                        <Check className="size-3.5" />
                      </IconButton>
                    )}
                    <IconButton label="삭제" onClick={() => void run(notificationApi.remove(n.id))}>
                      <X className="size-3.5" />
                    </IconButton>
                  </div>
                </li>
              )
            })}
          </ul>
        </div>
      )}
    </Menu>
  )
}
