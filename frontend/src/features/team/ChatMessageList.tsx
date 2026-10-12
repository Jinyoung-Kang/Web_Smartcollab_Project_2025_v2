import { Fragment } from 'react'
import { Download } from 'lucide-react'
import { fileApi } from '@/api/endpoints'
import { Avatar } from '@/components/ui/misc'
import { ItemIcon } from '@/lib/fileIcons'
import { formatBytes, formatDay, formatTime } from '@/lib/format'
import { cn } from '@/lib/cn'
import type { ChatRow } from './chatMessages'

/**
 * 채팅 메시지 줄들(날짜 구분선·말풍선·공유 파일). 공유된 파일은 눌러서 미리보기를 열고, 옆의 아이콘으로 바로 내려받습니다.
 *
 * @param openingFileId 미리보기를 여는 중인 파일
 */
export function ChatMessageList({ rows, openingFileId, onOpenFile }: {
  rows: ChatRow[]
  openingFileId: number | null
  onOpenFile: (fileId: number) => void
}) {
  return rows.map(({ message: m, mine, newDay, grouped }) => (
    <Fragment key={m.id}>
      {newDay && (
        <div className="my-4 flex items-center gap-3 text-xs text-slate-500">
          <span className="h-px flex-1 bg-slate-200" />
          {formatDay(m.createdAt)}
          <span className="h-px flex-1 bg-slate-200" />
        </div>
      )}
      <div className={cn('flex gap-2', mine ? 'flex-row-reverse' : '', grouped ? 'mt-1' : 'mt-3')}>
        {!mine && (grouped ? <span className="w-8 shrink-0" /> : <Avatar name={m.sender.name} />)}
        <div className={cn('flex max-w-[78%] flex-col', mine ? 'items-end' : 'items-start')}>
          {!mine && !grouped && <span className="mb-1 text-xs font-medium text-slate-500">{m.sender.name}</span>}
          <div className="flex items-end gap-1.5">
            {mine && <time className="text-[11px] whitespace-nowrap text-slate-500">{formatTime(m.createdAt)}</time>}
            {m.type === 'FILE_SHARE' && m.file ? (
              <div className={cn('flex items-center rounded-2xl border text-sm hover:shadow-sm',
                mine ? 'border-brand-200 bg-brand-50' : 'border-slate-200 bg-white')}>
                <button
                  type="button"
                  onClick={() => onOpenFile(m.file!.id)}
                  aria-label={`${m.file.name} 미리보기`}
                  aria-busy={openingFileId === m.file.id}
                  className={cn('flex min-w-0 items-center gap-3 rounded-l-2xl py-2.5 pr-1 pl-3 text-left',
                    openingFileId === m.file.id && 'cursor-wait opacity-70')}
                >
                  <ItemIcon type="file" name={m.file.name} className="size-8" />
                  <span className="min-w-0">
                    <span className="block max-w-[12rem] truncate font-medium">{m.file.name}</span>
                    <span className={cn('text-xs', mine ? 'text-slate-600' : 'text-slate-500')}>{formatBytes(m.file.size)}</span>
                  </span>
                </button>
                <a
                  href={fileApi.downloadUrl(m.file.id)}
                  download
                  aria-label={`${m.file.name} 내려받기`}
                  title="내려받기"
                  className="mr-1.5 rounded-lg p-2 text-slate-500 hover:bg-slate-100 hover:text-slate-700"
                >
                  <Download aria-hidden className="size-4" />
                </a>
              </div>
            ) : (
              <p className={cn('rounded-2xl px-3.5 py-2 text-sm leading-relaxed whitespace-pre-wrap break-words',
                mine ? 'rounded-br-md bg-brand-600 text-white' : 'rounded-bl-md bg-slate-100 text-slate-800')}>
                {m.content}
              </p>
            )}
            {!mine && <time className="text-[11px] whitespace-nowrap text-slate-500">{formatTime(m.createdAt)}</time>}
          </div>
        </div>
      </div>
    </Fragment>
  ))
}
