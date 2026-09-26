import {
  File,
  FileArchive,
  FileCode2,
  FileImage,
  FileSpreadsheet,
  FileText,
  FileType,
  Folder,
  Presentation,
  type LucideIcon,
} from 'lucide-react'
import { cn } from './cn'

const BY_EXT: Record<string, { icon: LucideIcon; color: string }> = {}
const register = (exts: string[], icon: LucideIcon, color: string) => exts.forEach((e) => (BY_EXT[e] = { icon, color }))

register(['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg'], FileImage, 'text-violet-500')
register(['pdf'], FileType, 'text-red-500')
register(['txt', 'md', 'log', 'doc', 'docx', 'hwp'], FileText, 'text-sky-600')
register(['csv', 'xls', 'xlsx'], FileSpreadsheet, 'text-emerald-600')
register(['ppt', 'pptx'], Presentation, 'text-orange-500')
register(['zip', 'tar', 'gz', '7z', 'rar'], FileArchive, 'text-amber-700')
register(['json', 'js', 'ts', 'java', 'py', 'html', 'css', 'xml', 'yml', 'yaml'], FileCode2, 'text-slate-600')

export function extensionOf(name: string): string {
  const dot = name.lastIndexOf('.')
  return dot < 0 || dot === name.length - 1 ? '' : name.slice(dot + 1).toLowerCase()
}

export function ItemIcon({ type, name, className }: { type: 'file' | 'folder'; name: string; className?: string }) {
  if (type === 'folder') {
    return <Folder aria-hidden className={cn('shrink-0 fill-amber-200 text-amber-500', className)} />
  }
  const spec = BY_EXT[extensionOf(name)]
  const Icon = spec?.icon ?? File
  return <Icon aria-hidden className={cn('shrink-0', spec?.color ?? 'text-slate-500', className)} />
}
