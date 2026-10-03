// 인가 행렬 — 사용자·팀 조합마다 남의 자원 ID(또는 ID 두 개를 섞은 요청)로 모든 API 를 불러,
// 기대 상태 코드와 "부수 효과 없음"(마지막에 주인이 다시 조회)을 확인합니다.
// 실행: node qa/scripts/authz-matrix.mjs  (QA 스택 http://localhost:8080)
import { writeFileSync } from 'node:fs'
import { Client, table } from './lib.mjs'

const A = await new Client('A').signup() // 개인 자원 주인, 팀 T1 팀장
const B = await new Client('B').signup() // 외부인, 팀 T2 팀장
const C = await new Client('C').signup() // T1 의 읽기 전용 멤버
const D = await new Client('D').signup() // T2 멤버(모든 권한)

// ---- A 의 개인 자원
const aRoot = A.me.rootFolderId
const aFolder = await A.createFolder(aRoot, 'a-folder')
const aFile = (await A.upload(aRoot, 'a.txt', 'A secret v1', 'text/plain')).data
const aC1 = await A.get(`/api/files/${aFile.id}/content`)
await A.put(`/api/files/${aFile.id}/content`, { content: 'A secret v2', baseVersionId: aC1.data.versionId })
const aVersions = (await A.get(`/api/files/${aFile.id}/versions`)).data
const aV1 = aVersions.find((v) => !v.active).versionId
const aLink = (await A.post(`/api/files/${aFile.id}/share-links`, { password: 'pass1234' })).data
const aTrashFile = (await A.upload(aRoot, 'trash.txt', 'trashed')).data
await A.del(`/api/files/${aTrashFile.id}`)
const aTrashFolder = await A.createFolder(aRoot, 'trash-folder')
await A.del(`/api/folders/${aTrashFolder.id}`)

// ---- 팀 T1 (A 팀장, C 읽기 전용)
const T1 = await A.createTeam('qa-t1')
const cMember = await A.addMember(T1.id, C, { canEdit: false, canDelete: false, canInvite: false })
const t1File = (await A.upload(T1.rootFolderId, 'team.txt', 'team secret', 'text/plain')).data
const t1Folder = await A.createFolder(T1.rootFolderId, 't1-folder')
const t1Version = (await A.get(`/api/files/${t1File.id}/versions`)).data[0].versionId

// ---- B 의 개인 자원과 팀 T2 (B 팀장, D 멤버)
const bRoot = B.me.rootFolderId
const bFile = (await B.upload(bRoot, 'b.txt', 'B own v1', 'text/plain')).data
const bC1 = await B.get(`/api/files/${bFile.id}/content`)
await B.put(`/api/files/${bFile.id}/content`, { content: 'B own v2', baseVersionId: bC1.data.versionId })
const bLink = (await B.post(`/api/files/${bFile.id}/share-links`, { password: 'bpass1234' })).data
const T2 = await B.createTeam('qa-t2')
await B.addMember(T2.id, D, { canEdit: true, canDelete: true, canInvite: true })

// ---- 알림·초대: B 가 A 를 T2 에 초대(A 에게 온 알림·초대), A 가 B 를 T1 에 초대(B 에게 온 초대)
await B.post(`/api/teams/${T2.id}/invitations`, { username: A.username })
await A.post(`/api/teams/${T1.id}/invitations`, { username: B.username })
const aNotif = (await A.get('/api/notifications')).data.items.find((n) => n.invitationId)
const bNotif = (await B.get('/api/notifications')).data.items.find((n) => n.invitationId)

const OUT = [404]
const DENY = [403, 404]
const REJECT = [400, 403, 404, 409]
const checks = []
const add = (who, method, path, json, expect, note = '') => checks.push({ who, method, path, json, expect, note })

// 1) 외부인 B 가 A 의 개인 자원에
add(B, 'GET', `/api/folders/${aRoot}`, undefined, OUT)
add(B, 'GET', `/api/folders/${aFolder.id}`, undefined, OUT)
add(B, 'POST', '/api/folders', { parentId: aFolder.id, name: 'x' }, OUT)
add(B, 'PATCH', `/api/folders/${aFolder.id}`, { name: 'hacked' }, OUT)
add(B, 'DELETE', `/api/folders/${aFolder.id}`, undefined, OUT)
add(B, 'GET', `/api/files/${aFile.id}`, undefined, OUT)
add(B, 'GET', `/api/files/${aFile.id}/download`, undefined, OUT)
add(B, 'GET', `/api/files/${aFile.id}/view`, undefined, OUT)
add(B, 'GET', `/api/files/${aFile.id}/office-preview-url`, undefined, [404, 503])
add(B, 'GET', `/api/files/${aFile.id}/content`, undefined, OUT)
add(B, 'GET', `/api/files/${aFile.id}/versions`, undefined, OUT)
add(B, 'GET', `/api/files/${aFile.id}/share-links`, undefined, OUT)
add(B, 'PATCH', `/api/files/${aFile.id}`, { name: 'hacked.txt' }, OUT)
add(B, 'PUT', `/api/files/${aFile.id}/content`, { content: 'hacked', baseVersionId: aVersions[0].versionId }, OUT)
add(B, 'POST', `/api/files/${aFile.id}/versions/${aV1}/restore`, undefined, OUT)
add(B, 'POST', `/api/files/${aFile.id}/signatures`, undefined, OUT)
add(B, 'POST', `/api/files/${aFile.id}/share-links`, {}, OUT)
add(B, 'POST', `/api/files/${aFile.id}/summary`, undefined, OUT)
add(B, 'POST', `/api/files/${aFile.id}/translation?target=EN`, undefined, OUT)
add(B, 'POST', '/api/items/move', { items: [{ type: 'file', id: aFile.id }], targetFolderId: bRoot }, OUT)
add(B, 'POST', '/api/items/copy', { items: [{ type: 'file', id: aFile.id }], targetFolderId: bRoot }, OUT)
add(B, 'POST', '/api/items/copy', { items: [{ type: 'folder', id: aFolder.id }], targetFolderId: bRoot }, OUT)
add(B, 'POST', '/api/items/delete', { items: [{ type: 'file', id: aFile.id }] }, OUT)
add(B, 'POST', '/api/items/move', { items: [{ type: 'file', id: bFile.id }], targetFolderId: aFolder.id }, OUT, '내 파일을 남의 폴더로')
add(B, 'POST', '/api/items/copy', { items: [{ type: 'file', id: bFile.id }], targetFolderId: aFolder.id }, OUT, '내 파일을 남의 폴더로 복사')
add(B, 'POST', `/api/trash/${aTrashFile.id}/restore`, undefined, OUT)
add(B, 'DELETE', `/api/trash/${aTrashFile.id}`, undefined, OUT)
add(B, 'POST', `/api/trash/folders/${aTrashFolder.id}/restore`, undefined, OUT)
add(B, 'DELETE', `/api/trash/folders/${aTrashFolder.id}`, undefined, OUT)
add(B, 'DELETE', `/api/share-links/${aLink.id}`, undefined, OUT)
add(B, 'POST', `/api/notifications/${aNotif.id}/read`, undefined, OUT)
add(B, 'DELETE', `/api/notifications/${aNotif.id}`, undefined, OUT)
add(C, 'POST', `/api/invitations/${aNotif.invitationId}/accept`, undefined, OUT, 'A 에게 온 초대를 C 가 수락')
add(C, 'POST', `/api/invitations/${aNotif.invitationId}/reject`, undefined, OUT, 'A 에게 온 초대를 C 가 거절')
add(B, 'POST', `/api/invitations/${aNotif.invitationId}/accept`, undefined, OUT, '보낸 사람(B)이 자기 초대를 수락')

// 2) 외부인 B 가 팀 T1 에
add(B, 'GET', `/api/teams/${T1.id}`, undefined, OUT)
add(B, 'GET', `/api/teams/${T1.id}/presence`, undefined, OUT)
add(B, 'GET', `/api/teams/${T1.id}/messages`, undefined, OUT)
add(B, 'POST', `/api/teams/${T1.id}/messages`, { content: 'hi' }, OUT)
add(B, 'DELETE', `/api/teams/${T1.id}/messages`, undefined, OUT)
add(B, 'POST', `/api/teams/${T1.id}/invitations`, { username: B.username }, OUT, '스스로 초대')
add(B, 'PUT', `/api/teams/${T1.id}/members/${cMember.memberId}/permissions`, { canEdit: true, canDelete: true, canInvite: true }, OUT)
add(B, 'DELETE', `/api/teams/${T1.id}/members/${cMember.memberId}`, undefined, OUT)
add(B, 'POST', `/api/teams/${T1.id}/leave`, undefined, OUT)
add(B, 'POST', `/api/teams/${T1.id}/leader/${cMember.memberId}`, undefined, OUT)
add(B, 'DELETE', `/api/teams/${T1.id}`, undefined, OUT)
add(B, 'GET', `/api/folders/tree?teamId=${T1.id}`, undefined, DENY)
add(B, 'GET', `/api/files/search?q=team&teamId=${T1.id}`, undefined, DENY)
add(B, 'GET', `/api/files/usage?teamId=${T1.id}`, undefined, DENY)
add(B, 'GET', `/api/trash?teamId=${T1.id}`, undefined, DENY)
add(B, 'DELETE', `/api/trash?teamId=${T1.id}`, undefined, DENY)
add(B, 'GET', `/api/folders/${T1.rootFolderId}`, undefined, OUT)
add(B, 'GET', `/api/files/${t1File.id}/download`, undefined, OUT)

// 3) ID 두 개를 섞은 요청 — 경로의 앞 ID 는 내 것, 뒤 ID(또는 본문)는 남의 것
add(B, 'PUT', `/api/teams/${T2.id}/members/${cMember.memberId}/permissions`, { canEdit: true, canDelete: true, canInvite: true }, REJECT, 'T2 경로 + T1 멤버')
add(B, 'DELETE', `/api/teams/${T2.id}/members/${cMember.memberId}`, undefined, REJECT, 'T2 경로 + T1 멤버')
add(B, 'POST', `/api/teams/${T2.id}/leader/${cMember.memberId}`, undefined, REJECT, 'T2 경로 + T1 멤버')
add(B, 'POST', `/api/files/${bFile.id}/versions/${aV1}/restore`, undefined, REJECT, 'B 파일 + A 파일의 버전')
add(B, 'PUT', `/api/files/${bFile.id}/content`, { content: 'x', baseVersionId: aV1 }, REJECT, 'B 파일 + A 파일의 버전을 기준으로')
add(B, 'POST', `/api/teams/${T2.id}/messages`, { fileId: aFile.id }, REJECT, 'T2 채팅에 A 개인 파일')
add(B, 'POST', `/api/teams/${T2.id}/messages`, { fileId: t1File.id }, REJECT, 'T2 채팅에 T1 파일')
add(B, 'POST', `/api/teams/${T2.id}/messages`, { fileId: bFile.id }, REJECT, 'T2 채팅에 B 개인 파일')

// 4) T1 의 읽기 전용 멤버 C 의 쓰기
add(C, 'POST', '/api/folders', { parentId: T1.rootFolderId, name: 'c' }, [403])
add(C, 'PATCH', `/api/folders/${t1Folder.id}`, { name: 'c' }, [403])
add(C, 'DELETE', `/api/folders/${t1Folder.id}`, undefined, [403])
add(C, 'PATCH', `/api/files/${t1File.id}`, { name: 'c.txt' }, [403])
add(C, 'DELETE', `/api/files/${t1File.id}`, undefined, [403])
add(C, 'POST', '/api/items/delete', { items: [{ type: 'file', id: t1File.id }] }, [403])
add(C, 'POST', '/api/items/move', { items: [{ type: 'file', id: t1File.id }], targetFolderId: C.me.rootFolderId }, [403], '팀 파일을 내 드라이브로 이동')
add(C, 'PUT', `/api/files/${t1File.id}/content`, { content: 'c', baseVersionId: t1Version }, [403])
add(C, 'POST', `/api/files/${t1File.id}/versions/${t1Version}/restore`, undefined, [403, 409])
add(C, 'POST', `/api/files/${t1File.id}/signatures`, undefined, [403])
add(C, 'POST', `/api/teams/${T1.id}/invitations`, { username: D.username }, [403])
add(C, 'PUT', `/api/teams/${T1.id}/members/${cMember.memberId}/permissions`, { canEdit: true, canDelete: true, canInvite: true }, [403], '자기 권한 올리기')
add(C, 'DELETE', `/api/teams/${T1.id}/messages`, undefined, [403])
add(C, 'DELETE', `/api/teams/${T1.id}`, undefined, [403])
add(C, 'DELETE', `/api/trash?teamId=${T1.id}`, undefined, [403])
add(C, 'POST', `/api/teams/${T1.id}/leader/${cMember.memberId}`, undefined, [403])
add(C, 'POST', `/api/teams/${T1.id}/messages`, { fileId: t1File.id }, [200, 201], '읽기 전용도 팀 파일 공유는 가능(기준)')

// 5) 공유 링크 grant 를 다른 링크에
const anon = new Client('anon')
const grantA = (await anon.post(`/api/public/shares/${aLink.token}/unlock`, { password: 'pass1234' })).data.grant
add(anon, 'GET', `/api/public/shares/${bLink.token}/download?grant=${encodeURIComponent(grantA)}`, undefined, [400, 401, 403, 404, 410], 'A 링크의 grant 로 B 링크 다운로드')
add(anon, 'GET', `/api/public/shares/${aLink.token}/download`, undefined, [400, 401, 403], 'grant 없이 비밀번호 링크')

const results = []
for (const c of checks) {
  const r = await c.who.req(c.method, c.path, { json: c.json })
  const ok = c.expect.includes(r.status)
  results.push({
    ok: ok ? 'PASS' : 'FAIL',
    who: c.who.label,
    req: `${c.method} ${c.path.replace(/grant=[^&]+/, 'grant=…')}`,
    expect: c.expect.join('/'),
    actual: r.status,
    note: c.note,
    body: ok ? '' : r.text.slice(0, 160),
  })
}

// ---- 부수 효과 확인 (주인이 다시 조회)
const effects = []
const expectEq = (name, actual, expected) =>
  effects.push({ ok: JSON.stringify(actual) === JSON.stringify(expected) ? 'PASS' : 'FAIL', name, actual: JSON.stringify(actual), expected: JSON.stringify(expected) })
expectEq('A 파일 내용', (await A.get(`/api/files/${aFile.id}/content`)).data.content, 'A secret v2')
expectEq('A 파일 이름', (await A.get(`/api/files/${aFile.id}`)).data.name, 'a.txt')
expectEq('A 폴더 이름', (await A.get(`/api/folders/${aFolder.id}`)).data.folder.name, 'a-folder')
expectEq('A 폴더 안 항목 수', (await A.get(`/api/folders/${aFolder.id}`)).data.items.length, 0)
expectEq('A 공유 링크 활성', (await A.get(`/api/files/${aFile.id}/share-links`)).data.find((l) => l.id === aLink.id)?.active, true)
const aTrash = (await A.get('/api/trash')).data
expectEq('A 휴지통 파일 그대로', aTrash.some((t) => t.type === 'file' && t.id === aTrashFile.id), true)
expectEq('A 휴지통 폴더 그대로', aTrash.some((t) => t.type === 'folder' && t.id === aTrashFolder.id), true)
const t1 = (await A.get(`/api/teams/${T1.id}`)).data
const c1 = t1.members.find((m) => m.memberId === cMember.memberId)
expectEq('T1 의 C 권한', [c1?.canEdit, c1?.canDelete, c1?.canInvite], [false, false, false])
expectEq('T1 팀장', t1.members.find((m) => m.leader)?.username, A.username)
expectEq('T1 멤버 수', t1.members.length, 2)
expectEq('T1 채팅에 B 메시지 없음', (await A.get(`/api/teams/${T1.id}/messages`)).data.messages.some((m) => m.sender?.username === B.username), false)
const aN = (await A.get('/api/notifications')).data.items.find((n) => n.id === aNotif.id)
expectEq('A 알림 그대로(안 읽음)', aN?.read, false)
expectEq('A 에게 온 초대 상태', aN?.invitationStatus, 'PENDING')
expectEq('B 파일 내용', (await B.get(`/api/files/${bFile.id}/content`)).data.content, 'B own v2')
const t2msgs = (await B.get(`/api/teams/${T2.id}/messages`)).data.messages
expectEq('T2 채팅에 남의 파일 없음', t2msgs.filter((m) => m.file && [aFile.id, t1File.id].includes(m.file.id)).length, 0)
expectEq('T2 팀장', (await B.get(`/api/teams/${T2.id}`)).data.members.find((m) => m.leader)?.username, B.username)
expectEq('B 에게 온 초대 그대로', (await B.get('/api/notifications')).data.items.find((n) => n.id === bNotif.id)?.invitationStatus, 'PENDING')

const fails = results.filter((r) => r.ok === 'FAIL')
const effectFails = effects.filter((e) => e.ok === 'FAIL')
const report =
  `# 인가 행렬 결과 (${new Date().toISOString()})\n\n요청 ${results.length}건 중 기대와 다름 ${fails.length}건, 부수 효과 확인 ${effects.length}건 중 실패 ${effectFails.length}건\n\n` +
  table(results, ['ok', 'who', 'req', 'expect', 'actual', 'note', 'body']) +
  '\n\n## 부수 효과\n\n' +
  table(effects, ['ok', 'name', 'expected', 'actual']) +
  '\n'
writeFileSync(new URL('../results/authz-matrix.md', import.meta.url), report)
console.log(`requests=${results.length} fail=${fails.length} effects=${effects.length} effectFail=${effectFails.length}`)
for (const f of fails) console.log('FAIL', f.who, f.req, 'expect', f.expect, 'actual', f.actual, f.note, f.body)
for (const e of effectFails) console.log('EFFECT FAIL', e.name, 'expected', e.expected, 'actual', e.actual)
