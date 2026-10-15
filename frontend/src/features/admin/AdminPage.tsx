import { useState } from 'react'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { LogOut, Users } from 'lucide-react'
import { adminApi } from '@/api/endpoints'
import type { AdminAccount } from '@/api/types'
import { queryKeys } from '@/api/queryKeys'
import { useAuth } from '@/auth/AuthProvider'
import { Button } from '@/components/ui/Button'
import { useConfirm } from '@/components/ui/Confirm'
import { useToast } from '@/components/ui/Toast'
import { Badge, EmptyState, Spinner } from '@/components/ui/misc'
import { formatBytes, formatDateTime } from '@/lib/format'
import { useDocumentTitle } from '@/lib/useDocumentTitle'

export const PAGE_SIZE = 20

/**
 * 관리 전용 계정의 화면 (ADR-0015) — 가입한 계정의 아이디·이름·소속 팀·드라이브 사용량·가입일을 보고 계정을 삭제합니다.
 * 서버가 파일·채팅 정보를 주지 않으므로 이 화면에서 볼 수 있는 것은 위 항목뿐입니다.
 */
export default function AdminPage() {
  useDocumentTitle('계정 관리')
  const { me, logout } = useAuth()
  const [page, setPage] = useState(0)
  const accounts = useQuery({
    queryKey: queryKeys.adminAccounts.page(page),
    queryFn: () => adminApi.accounts(page, PAGE_SIZE),
    placeholderData: keepPreviousData,
  })
  const qc = useQueryClient()
  const toast = useToast()
  const confirm = useConfirm()

  const remove = useMutation({
    mutationFn: (account: AdminAccount) => adminApi.deleteAccount(account.id),
    onSuccess: (result, account) => {
      const notes = [
        result.transferredTeams > 0 && `팀장 위임 ${result.transferredTeams}개 팀`,
        result.deletedTeams > 0 && `팀 삭제 ${result.deletedTeams}개`,
      ].filter(Boolean)
      toast.success(`${account.username} 계정을 삭제했습니다.${notes.length ? ` (${notes.join(', ')})` : ''}`)
    },
    onError: (e, account) => toast.error(`${account.username} 계정을 삭제하지 못했습니다. ${(e as Error).message}`),
    onSettled: () => qc.invalidateQueries({ queryKey: queryKeys.adminAccounts.all }),
  })

  // 마지막 쪽의 마지막 계정을 지워 쪽이 비면 앞 쪽으로 (렌더 중 상태 조정)
  if (accounts.data && accounts.data.items.length === 0 && page > 0 && !accounts.isPlaceholderData) {
    setPage(Math.max(0, accounts.data.totalPages - 1))
  }

  const askDelete = async (account: AdminAccount) => {
    const led = account.teams.filter((t) => t.leader).map((t) => t.name)
    const ok = await confirm({
      title: `${account.username} 계정 삭제`,
      message: (
        <>
          <p>이 계정의 개인 드라이브 파일이 모두 영구 삭제되고, 속한 팀에서 빠집니다. 팀에 올린 파일과 대화는 팀에 남고 작성자는
            &lsquo;탈퇴한 사용자&rsquo;로 바뀝니다.</p>
          {led.length > 0 && (
            <p className="mt-2">팀장인 팀({led.join(', ')})은 가장 먼저 들어온 멤버에게 팀장을 넘기고, 다른 멤버가 없으면 팀을 삭제합니다.</p>
          )}
          <p className="mt-2 font-medium text-red-700">되돌릴 수 없습니다. 확인하려면 아이디를 입력하세요.</p>
        </>
      ),
      confirmLabel: '계정 삭제',
      danger: true,
      requireText: account.username,
    })
    if (ok) remove.mutate(account)
  }

  const data = accounts.data
  return (
    <div className="flex min-h-dvh flex-col bg-slate-50">
      <header className="flex h-14 shrink-0 items-center gap-3 border-b border-slate-200 bg-white px-3 sm:px-4">
        <span className="font-bold text-slate-900">SmartCollab</span>
        <Badge tone="brand">관리</Badge>
        <div className="ml-auto flex min-w-0 items-center gap-2">
          <span className="hidden min-w-0 truncate text-sm text-slate-600 sm:inline">@{me?.username}</span>
          <Button size="sm" variant="ghost" onClick={() => void logout()}>
            <LogOut aria-hidden className="size-4" /> 로그아웃
          </Button>
        </div>
      </header>

      <main className="mx-auto w-full max-w-6xl flex-1 px-4 py-6 sm:px-6">
        <h1 className="text-lg font-semibold">계정 관리</h1>
        <p className="mt-1 text-sm text-slate-600">
          가입한 계정의 아이디·이름·소속 팀·드라이브 사용량·가입일입니다. 계정의 파일과 대화는 볼 수 없습니다.
        </p>

        <section aria-labelledby="accounts-heading" className="mt-5 rounded-xl border border-slate-200 bg-white">
          <div className="flex flex-wrap items-center gap-2 border-b border-slate-200 px-4 py-3">
            <h2 id="accounts-heading" className="text-sm font-semibold">계정 목록</h2>
            {data && <span className="text-sm text-slate-600">전체 {data.totalElements}명</span>}
          </div>
          {accounts.isPending && <Spinner className="p-6" />}
          {accounts.isError && (
            <div role="alert" className="flex flex-wrap items-center gap-3 p-6 text-sm text-red-600">
              계정 목록을 불러오지 못했습니다. {accounts.error.message}
              <Button size="sm" onClick={() => void accounts.refetch()}>다시 시도</Button>
            </div>
          )}
          {data?.totalElements === 0 && <EmptyState icon={Users} title="가입한 계정이 없습니다" />}
          {data && data.items.length > 0 && (
            // relative: 머리글의 화면 낭독기용 글자(sr-only, 절대 위치)가 이 스크롤 상자를 기준으로 놓이게 합니다 — 없으면
            // 상자 밖(문서)을 기준으로 놓여 좁은 화면에서 문서 전체가 가로로 넓어졌습니다
            <div className="relative overflow-x-auto">
              <table className="w-full min-w-[880px] text-left text-sm">
                <thead className="bg-slate-50 text-xs text-slate-600">
                  <tr>
                    <th scope="col" className="px-4 py-2.5 font-medium">아이디</th>
                    <th scope="col" className="px-4 py-2.5 font-medium">이름</th>
                    <th scope="col" className="px-4 py-2.5 font-medium">소속 팀</th>
                    <th scope="col" className="px-4 py-2.5 font-medium">드라이브 사용량</th>
                    <th scope="col" className="px-4 py-2.5 font-medium">가입일</th>
                    <th scope="col" className="px-4 py-2.5 font-medium"><span className="sr-only">관리</span></th>
                  </tr>
                </thead>
                <tbody>
                  {data.items.map((a) => (
                    <tr key={a.id} className="border-t border-slate-100 align-top">
                      <td className="max-w-48 px-4 py-3">
                        <span className="block truncate font-mono text-xs" title={a.username}>{a.username}</span>
                        {a.demo && <span className="mt-1 inline-block"><Badge tone="amber">체험 계정</Badge></span>}
                      </td>
                      <td className="max-w-40 px-4 py-3"><span className="block truncate" title={a.name}>{a.name}</span></td>
                      <td className="max-w-64 min-w-40 px-4 py-3">
                        {a.teams.length === 0 ? (
                          <span className="text-slate-500">없음</span>
                        ) : (
                          <ul className="grid grid-cols-1 gap-1">
                            {a.teams.map((t, i) => (
                              <li key={`${t.name}-${i}`} className="flex min-w-0 items-center gap-1.5">
                                <span className="truncate" title={t.name}>{t.name}</span>
                                {t.leader && <Badge tone="brand">팀장</Badge>}
                              </li>
                            ))}
                          </ul>
                        )}
                      </td>
                      <td className="px-4 py-3 whitespace-nowrap">
                        {formatBytes(a.storedBytes)} <span className="text-slate-500">/ {formatBytes(a.quotaBytes)}</span>
                      </td>
                      <td className="px-4 py-3 whitespace-nowrap">{formatDateTime(a.createdAt)}</td>
                      <td className="px-4 py-3 text-right">
                        <Button
                          size="sm"
                          variant="ghost"
                          className="text-red-600 hover:bg-red-50"
                          disabled={a.demo}
                          title={a.demo ? '체험 계정은 매일 초기화되므로 삭제할 수 없습니다' : undefined}
                          loading={remove.isPending && remove.variables?.id === a.id}
                          // 보이는 글자("삭제")를 이름에 포함해 음성 조작으로도 누를 수 있게 합니다(WCAG 2.5.3) [QA-13]
                          aria-label={`${a.username} 계정 삭제`}
                          onClick={() => void askDelete(a)}
                        >
                          삭제
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {data && data.totalPages > 1 && (
            <nav aria-label="계정 목록 쪽" className="flex items-center justify-end gap-2 border-t border-slate-200 px-4 py-3 text-sm">
              <Button size="sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>이전</Button>
              <span className="text-slate-600">{page + 1} / {data.totalPages}</span>
              <Button size="sm" disabled={page + 1 >= data.totalPages} onClick={() => setPage((p) => p + 1)}>다음</Button>
            </nav>
          )}
        </section>
      </main>
    </div>
  )
}
