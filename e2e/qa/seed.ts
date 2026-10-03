// QA 화면 점검용 데이터 — API 로 사용자·폴더·파일·팀·채팅·공유 링크·휴지통·알림을 만듭니다(QA 스택 전용).
// @ts-expect-error 같은 저장소의 QA 점검 스크립트(.mjs)를 그대로 씁니다
import { Client, PASSWORD } from '../../qa/scripts/lib.mjs'

// 1x1 PNG
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64')

export interface Seed {
  user: { username: string; password: string; rootFolderId: number }
  member: { username: string; password: string }
  folderId: number
  textFileId: number
  imageFileId: number
  teamId: number
  teamRootFolderId: number
  shareToken: string
  sharePassword: string
}

export async function seed(): Promise<Seed> {
  const X = await new Client('X').signup(undefined, 'QA 사용자 <b>굵게</b>')
  const Y = await new Client('Y').signup(undefined, '팀원 "따옴표"')
  const root = X.me.rootFolderId
  const folder = await X.createFolder(root, 'QA 폴더 <img src=x onerror=alert(1)>')
  await X.createFolder(folder.id, '하위 폴더')
  const text = (await X.upload(root, '회의록.txt', '첫 문장입니다. 두 번째 문장은 조금 더 깁니다. 세 번째 문장으로 끝납니다.', 'text/plain')).data
  const c = await X.get(`/api/files/${text.id}/content`)
  await X.put(`/api/files/${text.id}/content`, { content: '수정한 회의록입니다. 결정 사항을 정리했습니다.', baseVersionId: c.data.versionId })
  const image = (await X.upload(root, '스케치.png', PNG, 'image/png')).data
  await X.upload(folder.id, '보고서.md', '# 제목\n\n본문', 'text/markdown')
  for (let i = 0; i < 25; i++) await X.upload(folder.id, `데이터-${String(i).padStart(2, '0')}.csv`, `a,b\n${i},${i}`, 'text/csv')
  const link = (await X.post(`/api/files/${text.id}/share-links`, { password: 'share1234', downloadLimit: 5 })).data
  const trashed = (await X.upload(root, '지운 파일.txt', 'x', 'text/plain')).data
  await X.del(`/api/files/${trashed.id}`)
  const trashedFolder = await X.createFolder(root, '지운 폴더')
  await X.del(`/api/folders/${trashedFolder.id}`)
  const team = await X.createTeam('QA 팀 <script>alert(1)</script>')
  await X.addMember(team.id, Y, { canEdit: true, canDelete: false, canInvite: false })
  const teamFile = (await X.upload(team.rootFolderId, '팀 문서.txt', '팀 문서 내용', 'text/plain')).data
  await X.post(`/api/teams/${team.id}/messages`, { content: '안녕하세요 <img src=x onerror=alert(1)> **굵게**' })
  await Y.post(`/api/teams/${team.id}/messages`, { content: '반갑습니다\n여러 줄\n메시지' })
  await X.post(`/api/teams/${team.id}/messages`, { fileId: teamFile.id })
  // X 에게 온 초대(알림)
  const Z = await new Client('Z').signup()
  const zTeam = await Z.createTeam('초대한 팀')
  await Z.post(`/api/teams/${zTeam.id}/invitations`, { username: X.username })
  return {
    user: { username: X.username, password: PASSWORD, rootFolderId: root },
    member: { username: Y.username, password: PASSWORD },
    folderId: folder.id,
    textFileId: text.id,
    imageFileId: image.id,
    teamId: team.id,
    teamRootFolderId: team.rootFolderId,
    shareToken: link.token,
    sharePassword: 'share1234',
  }
}
