import { http } from './http'
import type {
  AppNotification,
  ChatMessage,
  ChatPage,
  DeleteItemsResult,
  FolderContents,
  FolderSortKey,
  Item,
  ItemRef,
  Me,
  NotificationList,
  PublicConfig,
  PublicShareInfo,
  RestoreResult,
  SearchResult,
  ShareLink,
  Summary,
  TeamDetail,
  TeamSummary,
  TextContent,
  Translation,
  TrashItem,
  TreeNode,
  Usage,
  Version,
} from './types'

const q = (params: Record<string, string | number | undefined | null>) => {
  const s = new URLSearchParams()
  Object.entries(params).forEach(([k, v]) => v !== undefined && v !== null && s.set(k, String(v)))
  const str = s.toString()
  return str ? `?${str}` : ''
}

export const authApi = {
  me: () => http.get<Me>('/api/auth/me', { quiet401: true }),
  /** 로그인 여부 — 로그인 전에도 200(콘솔에 401 오류가 찍히지 않음) [IMP-10] */
  session: () => http.get<{ authenticated: boolean; user?: Me }>('/api/auth/session'),
  login: (username: string, password: string) => http.post<Me>('/api/auth/login', { username, password }),
  signup: (body: { username: string; password: string; passwordConfirm: string; name: string; email?: string }) =>
    http.post<Me>('/api/auth/signup', body),
  logout: () => http.post<void>('/api/auth/logout'),
  deleteAccount: (password: string) => http.post<void>('/api/users/me/delete', { password }),
}

export const configApi = {
  get: () => http.get<PublicConfig>('/api/public/config'),
}

export const folderApi = {
  /** 폴더 내용 한 묶음 — 정렬은 서버가 하고, 다음 묶음은 nextCursor 로 [IMP-02] */
  contents: (folderId: number, page: { sort: FolderSortKey; order: 'asc' | 'desc'; cursor?: string; limit?: number }) =>
    http.get<FolderContents>(`/api/folders/${folderId}${q({ sort: page.sort, order: page.order, cursor: page.cursor, limit: page.limit })}`),
  tree: (teamId?: number) => http.get<{ roots: TreeNode[] }>(`/api/folders/tree${q({ teamId })}`),
  create: (parentId: number, name: string) => http.post<Item>('/api/folders', { parentId, name }),
  rename: (folderId: number, name: string) => http.patch<void>(`/api/folders/${folderId}`, { name }),
}

export const fileApi = {
  /** 파일 한 개의 지금 정보 (채팅에 공유된 파일 미리보기) */
  get: (fileId: number) => http.get<Item>(`/api/files/${fileId}`),
  rename: (fileId: number, name: string) => http.patch<void>(`/api/files/${fileId}`, { name }),
  content: (fileId: number) => http.get<TextContent>(`/api/files/${fileId}/content`),
  save: (fileId: number, content: string, baseVersionId: number) =>
    http.put<{ versionId: number; updatedAt: string }>(`/api/files/${fileId}/content`, { content, baseVersionId }),
  versions: (fileId: number) => http.get<Version[]>(`/api/files/${fileId}/versions`),
  restore: (fileId: number, versionId: number) => http.post<void>(`/api/files/${fileId}/versions/${versionId}/restore`),
  sign: (fileId: number) => http.post<void>(`/api/files/${fileId}/signatures`),
  officePreviewUrl: (fileId: number) => http.get<{ url: string }>(`/api/files/${fileId}/office-preview-url`),
  search: (query: string, teamId?: number) => http.get<SearchResult[]>(`/api/files/search${q({ q: query, teamId })}`),
  usage: (teamId?: number) => http.get<Usage>(`/api/files/usage${q({ teamId })}`),
  summary: (fileId: number) => http.post<Summary>(`/api/files/${fileId}/summary`),
  translate: (fileId: number, target: 'EN' | 'KO') => http.post<Translation>(`/api/files/${fileId}/translation${q({ target })}`),
  downloadUrl: (fileId: number) => `/api/files/${fileId}/download`,
  viewUrl: (fileId: number) => `/api/files/${fileId}/view`,
}

export const itemApi = {
  move: (items: ItemRef[], targetFolderId: number) => http.post<void>('/api/items/move', { items, targetFolderId }),
  /** 파일·폴더를 휴지통으로(폴더는 안의 파일과 함께). 하나라도 실패하면 아무것도 지우지 않습니다 [PERF-03·UX-06] */
  remove: (items: ItemRef[]) => http.post<DeleteItemsResult>('/api/items/delete', { items }),
  copy: (items: ItemRef[], targetFolderId: number) =>
    http.post<{ copiedFiles: number }>('/api/items/copy', { items, targetFolderId }),
}

export const trashApi = {
  list: (teamId?: number) => http.get<TrashItem[]>(`/api/trash${q({ teamId })}`),
  restore: (fileId: number) => http.post<void>(`/api/trash/${fileId}/restore`),
  purge: (fileId: number) => http.del<void>(`/api/trash/${fileId}`),
  restoreFolder: (folderId: number) => http.post<RestoreResult>(`/api/trash/folders/${folderId}/restore`),
  purgeFolder: (folderId: number) => http.del<void>(`/api/trash/folders/${folderId}`),
  empty: (teamId?: number) => http.del<{ deleted: number }>(`/api/trash${q({ teamId })}`),
}

export const teamApi = {
  list: () => http.get<TeamSummary[]>('/api/teams'),
  create: (name: string) => http.post<TeamSummary>('/api/teams', { name }),
  detail: (teamId: number) => http.get<TeamDetail>(`/api/teams/${teamId}`),
  presence: (teamId: number) => http.get<{ online: string[] }>(`/api/teams/${teamId}/presence`),
  invite: (teamId: number, username: string) => http.post<void>(`/api/teams/${teamId}/invitations`, { username }),
  updatePermissions: (teamId: number, memberId: number, p: { canEdit: boolean; canDelete: boolean; canInvite: boolean }) =>
    http.put<void>(`/api/teams/${teamId}/members/${memberId}/permissions`, p),
  removeMember: (teamId: number, memberId: number) => http.del<void>(`/api/teams/${teamId}/members/${memberId}`),
  leave: (teamId: number) => http.post<void>(`/api/teams/${teamId}/leave`),
  delegate: (teamId: number, memberId: number) => http.post<void>(`/api/teams/${teamId}/leader/${memberId}`),
  remove: (teamId: number) => http.del<void>(`/api/teams/${teamId}`),
  acceptInvitation: (id: number) => http.post<void>(`/api/invitations/${id}/accept`),
  rejectInvitation: (id: number) => http.post<void>(`/api/invitations/${id}/reject`),
}

export const chatApi = {
  history: (teamId: number, before?: number) => http.get<ChatPage>(`/api/teams/${teamId}/messages${q({ before, size: 30 })}`),
  send: (teamId: number, body: { content?: string; fileId?: number }) =>
    http.post<ChatMessage>(`/api/teams/${teamId}/messages`, body),
  clear: (teamId: number) => http.del<void>(`/api/teams/${teamId}/messages`),
}

export const notificationApi = {
  list: () => http.get<NotificationList>('/api/notifications'),
  read: (id: number) => http.post<void>(`/api/notifications/${id}/read`),
  readAll: () => http.post<void>('/api/notifications/read-all'),
  remove: (id: number) => http.del<void>(`/api/notifications/${id}`),
  removeAll: () => http.del<void>('/api/notifications'),
}

export const shareApi = {
  list: (fileId: number) => http.get<ShareLink[]>(`/api/files/${fileId}/share-links`),
  create: (fileId: number, body: { password?: string; expiresInHours?: number; downloadLimit?: number }) =>
    http.post<ShareLink>(`/api/files/${fileId}/share-links`, body),
  revoke: (linkId: number) => http.del<void>(`/api/share-links/${linkId}`),
  publicInfo: (token: string) => http.get<PublicShareInfo>(`/api/public/shares/${token}`),
  unlock: (token: string, password?: string) => http.post<{ grant: string }>(`/api/public/shares/${token}/unlock`, { password }),
  downloadUrl: (token: string, grant?: string) => `/api/public/shares/${token}/download${q({ grant })}`,
}

export type { AppNotification }
