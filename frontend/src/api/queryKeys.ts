/**
 * 쿼리 키를 한 곳에 모읍니다. 흩어진 문자열 배열로는 무효화할 때 모양(['tree', 'personal'] 과 ['tree', 3])을 틀리기
 * 쉬웠습니다. 앞부분만 같은 키(`all`)로 무효화하면 그 아래가 모두 무효화됩니다(예: 모든 폴더 내용).
 */
export type Scope = number | 'personal'

/** 팀 ID 가 없으면 개인 스토리지 */
export const scopeOf = (teamId?: number): Scope => teamId ?? 'personal'

export const queryKeys = {
  me: ['me'] as const,
  config: ['config'] as const,
  notifications: ['notifications'] as const,
  teams: ['teams'] as const,
  team: (teamId: number | undefined) => ['team', teamId] as const,
  presence: (teamId: number) => ['presence', teamId] as const,
  chat: { all: ['chat'] as const, of: (teamId: number) => ['chat', teamId] as const },
  folder: { all: ['folder'] as const, of: (folderId: number) => ['folder', folderId] as const },
  tree: { all: ['tree'] as const, of: (scope: Scope) => ['tree', scope] as const },
  usage: { all: ['usage'] as const, of: (scope: Scope) => ['usage', scope] as const },
  trash: { all: ['trash'] as const, of: (scope: Scope) => ['trash', scope] as const },
  versions: { all: ['versions'] as const, of: (fileId: number | undefined) => ['versions', fileId] as const },
  fileContent: (fileId: number | undefined) => ['file-content', fileId] as const,
  shareLinks: (fileId: number) => ['share-links', fileId] as const,
  officeUrl: (fileId: number | undefined) => ['office-url', fileId] as const,
  search: (query: string, teamId: number | undefined) => ['search', query, teamId] as const,
  publicShare: (token: string) => ['share', token] as const,
}
