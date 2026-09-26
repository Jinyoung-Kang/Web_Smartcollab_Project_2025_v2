import { useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { SearchX } from 'lucide-react'
import { fileApi, teamApi } from '@/api/endpoints'
import type { Item } from '@/api/types'
import { EmptyState, Spinner } from '@/components/ui/misc'
import { ItemIcon } from '@/lib/fileIcons'
import { formatBytes, formatRelative } from '@/lib/format'
import { PreviewDialog } from './dialogs/PreviewDialog'

/** 검색 결과. 하위 폴더까지 이름으로 찾고 파일이 있는 경로를 보여 줍니다. */
export default function SearchPage() {
  const [params] = useSearchParams()
  const q = params.get('q') ?? ''
  const teamId = params.get('teamId') ? Number(params.get('teamId')) : undefined
  const [preview, setPreview] = useState<Item | null>(null)
  const team = useQuery({ queryKey: ['team', teamId], queryFn: () => teamApi.detail(teamId!), enabled: !!teamId })
  const results = useQuery({ queryKey: ['search', q, teamId], queryFn: () => fileApi.search(q, teamId), enabled: q.length > 0 })

  const base = teamId ? `/teams/${teamId}/folders` : '/drive'

  return (
    <div className="flex h-full flex-col bg-white">
      <div className="border-b border-slate-200 px-4 py-4 sm:px-6">
        <h1 className="text-lg font-semibold">‘{q}’ 검색 결과</h1>
        <p className="text-sm text-slate-500">
          {teamId ? `${team.data?.name ?? '팀'} 스토리지` : '내 드라이브'}
          {results.data && ` · ${results.data.length}건${results.data.length === 100 ? ' (최대 100건 표시)' : ''}`}
        </p>
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto">
        {results.isPending && q && <Spinner className="p-6" />}
        {results.data?.length === 0 && <EmptyState icon={SearchX} title="일치하는 파일이 없습니다" description="다른 검색어를 입력해 보세요." />}
        <ul>
          {results.data?.map((r) => (
            <li key={r.item.id} className="flex items-center gap-3 border-b border-slate-100 px-4 py-3 hover:bg-slate-50 sm:px-6">
              <ItemIcon type="file" name={r.item.name} className="size-5" />
              <div className="min-w-0 flex-1">
                <button className="truncate text-left text-sm font-medium hover:text-brand-700 hover:underline" onClick={() => setPreview(r.item)}>
                  {r.item.name}
                </button>
                <p className="text-xs text-slate-500">
                  <Link to={`${base}/${r.folderId}`} className="hover:underline">{r.path === '/' ? '최상위 폴더' : r.path}</Link>
                  {' · '}
                  {r.item.ownerName} · {formatRelative(r.item.updatedAt)} · {formatBytes(r.item.size)}
                </p>
              </div>
            </li>
          ))}
        </ul>
      </div>
      <PreviewDialog file={preview} onClose={() => setPreview(null)} />
    </div>
  )
}
