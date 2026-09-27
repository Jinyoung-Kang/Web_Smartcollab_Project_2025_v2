// 백엔드 DTO 와 1:1 로 대응하는 타입 정의

export type PreviewKind = 'IMAGE' | 'PDF' | 'TEXT' | 'OFFICE' | 'NONE'

export interface Me {
  id: number
  username: string
  name: string
  email?: string
  rootFolderId: number
  createdAt: string
}

export interface Permissions {
  canEdit: boolean
  canDelete: boolean
  canInvite: boolean
  leader: boolean
}

export interface Item {
  type: 'file' | 'folder'
  id: number
  name: string
  size?: number
  extension?: string
  ownerName: string
  createdAt: string
  updatedAt: string
  previewKind: PreviewKind
  textEditable: boolean
}

export interface Breadcrumb {
  id: number
  name: string
}

export interface FolderContents {
  folder: { id: number; name: string; teamId?: number; root: boolean }
  path: Breadcrumb[]
  items: Item[]
  permissions: Permissions
}

export interface TreeNode {
  id: number
  name: string
  children: TreeNode[]
}

export interface SearchResult {
  item: Item
  folderId: number
  path: string
}

export interface Usage {
  /** 휴지통을 뺀 파일 수·크기 */
  fileCount: number
  totalBytes: number
  /** 한도에 셈하는 실제 저장량 (옛 버전·휴지통 포함) */
  storedBytes: number
  quotaBytes: number
}

export interface TrashItem {
  id: number
  name: string
  size?: number
  extension?: string
  deletedAt: string
  deletedByName?: string
  folderId: number
}

export interface TextContent {
  name: string
  folderId: number
  teamId?: number
  content: string
  versionId: number
  editable: boolean
  updatedAt: string
}

export interface SignatureInfo {
  signerName: string
  signedAt: string
  valid: boolean
  sha256: string
}

export interface Version {
  versionId: number
  createdAt: string
  editorName: string
  size: number
  sha256: string
  active: boolean
  signatures: SignatureInfo[]
}

export interface TeamSummary {
  id: number
  name: string
  ownerName: string
  memberCount: number
  rootFolderId: number
  myPermissions: Permissions
}

export interface Member {
  memberId: number
  userId: number
  username: string
  name: string
  leader: boolean
  canEdit: boolean
  canDelete: boolean
  canInvite: boolean
  joinedAt: string
}

export interface TeamDetail {
  id: number
  name: string
  ownerUsername: string
  rootFolderId: number
  myPermissions: Permissions
  members: Member[]
}

export interface ChatMessage {
  id: number
  type: 'CHAT' | 'FILE_SHARE'
  content: string
  sender: { username: string; name: string }
  file?: { id: number; name: string; size: number }
  createdAt: string
}

export interface ChatPage {
  messages: ChatMessage[]
  hasMore: boolean
}

export type NotificationType =
  | 'TEAM_INVITE'
  | 'INVITE_ACCEPTED'
  | 'INVITE_REJECTED'
  | 'PERMISSION_CHANGED'
  | 'REMOVED_FROM_TEAM'
  | 'LEADERSHIP_TRANSFERRED'
  | 'TEAM_DELETED'

export interface AppNotification {
  id: number
  type: NotificationType
  content: string
  read: boolean
  invitationId?: number
  invitationStatus?: 'PENDING' | 'ACCEPTED' | 'REJECTED'
  teamId?: number
  createdAt: string
}

export interface NotificationList {
  items: AppNotification[]
  unreadCount: number
}

export interface ShareLink {
  id: number
  token: string
  path: string
  passwordProtected: boolean
  expiresAt?: string
  downloadLimit?: number
  downloadCount: number
  createdAt: string
  active: boolean
}

export interface PublicShareInfo {
  fileName: string
  size: number
  passwordProtected: boolean
  expiresAt?: string
  remainingDownloads?: number
}

export interface PublicConfig {
  translationEnabled: boolean
  officePreviewEnabled: boolean
  maxUploadBytes: number
  demo: { enabled: boolean; accounts: { username: string; name: string; role: string }[]; password?: string }
}

export interface Summary {
  sentences: string[]
  totalSentences: number
  method: string
}

export interface Translation {
  text: string
  targetLang: string
  detectedSourceLang?: string
}

export type ItemRef = { type: 'file' | 'folder'; id: number }

/** 여러 항목 삭제 결과 [PERF-03] */
export interface DeleteItemsResult {
  trashedFiles: number
  deletedFolders: number
}
