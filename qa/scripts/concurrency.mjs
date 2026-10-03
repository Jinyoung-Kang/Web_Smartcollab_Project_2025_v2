// 동시 요청 블랙박스 — 같은 요청이 동시에 들어올 때 데이터가 틀어지거나 사라지지 않는지(끝나고 정합성 검사)
// 실행: node qa/scripts/concurrency.mjs
import { execSync } from 'node:child_process'
import { writeFileSync } from 'node:fs'
import { Client, PASSWORD } from './lib.mjs'

const sh = (c) => execSync(c, { encoding: 'utf8' }).trim()
const integrity = () => JSON.parse(sh('qa/scripts/integrity-check.sh sc-qa'))
const tally = (xs) => xs.reduce((m, s) => ((m[s] = (m[s] ?? 0) + 1), m), {})
const out = {}

// C1. 같은 초대를 동시에 5번 수락
{
  const A = await new Client().signup()
  const B = await new Client().signup()
  const t = await A.createTeam('c1')
  await A.post(`/api/teams/${t.id}/invitations`, { username: B.username })
  const inv = (await B.get('/api/notifications')).data.items.find((n) => n.invitationId).invitationId
  const st = await Promise.all(Array.from({ length: 5 }, () => B.post(`/api/invitations/${inv}/accept`).then((r) => r.status)))
  const members = (await A.get(`/api/teams/${t.id}`)).data.members.filter((m) => m.username === B.username).length
  out.C1_doubleAccept = { statuses: tally(st), memberRows: members }
}

// C2. 팀장인 팀 9개에서 동시에 6개 만들기 → 10개를 넘지 않아야
{
  const A = await new Client().signup()
  for (let i = 0; i < 9; i++) await A.createTeam(`cap${i}`)
  const st = await Promise.all(Array.from({ length: 6 }, (_, i) => A.post('/api/teams', { name: `race${i}` }).then((r) => r.status)))
  const led = (await A.get('/api/teams')).data.length
  out.C2_teamCap = { statuses: tally(st), teams: led }
}

// C3. 같은 기준 버전으로 동시에 10번 저장 → 하나만 성공
{
  const A = await new Client().signup()
  const f = (await A.upload(A.me.rootFolderId, 'c3.txt', 'base', 'text/plain')).data
  const base = (await A.get(`/api/files/${f.id}/content`)).data.versionId
  const st = await Promise.all(Array.from({ length: 10 }, (_, i) => A.put(`/api/files/${f.id}/content`, { content: `edit ${i}`, baseVersionId: base }).then((r) => r.status)))
  const versions = (await A.get(`/api/files/${f.id}/versions`)).data.length
  out.C3_concurrentSave = { statuses: tally(st), versions }
}

// C4. 팀장 위임과 그 멤버의 나가기를 동시에 (10회)
{
  const rounds = []
  for (let i = 0; i < 10; i++) {
    const A = await new Client().signup()
    const C = await new Client().signup()
    const t = await A.createTeam(`c4-${i}`)
    const m = await A.addMember(t.id, C, { canEdit: true, canDelete: true, canInvite: true })
    const [d, l] = await Promise.all([A.post(`/api/teams/${t.id}/leader/${m.memberId}`).then((r) => r.status), C.post(`/api/teams/${t.id}/leave`).then((r) => r.status)])
    const detail = await A.get(`/api/teams/${t.id}`)
    const detailC = await C.get(`/api/teams/${t.id}`)
    const view = detail.status === 200 ? detail.data : detailC.data
    const leaderName = view?.members?.find((x) => x.leader)?.username
    rounds.push({ delegate: d, leave: l, leader: leaderName === C.username ? 'C' : leaderName === A.username ? 'A' : '없음', members: view?.members?.length })
  }
  out.C4_delegateVsLeave = { outcomes: tally(rounds.map((r) => `위임${r.delegate}/나가기${r.leave}→팀장${r.leader},멤버${r.members}`)) }
}

// C5. 폴더 X→Y, Y→X 동시 이동 (10회) → 순환 없음
{
  const A = await new Client().signup()
  const res = []
  for (let i = 0; i < 10; i++) {
    const x = await A.createFolder(A.me.rootFolderId, `x${i}`)
    const y = await A.createFolder(A.me.rootFolderId, `y${i}`)
    const st = await Promise.all([
      A.post('/api/items/move', { items: [{ type: 'folder', id: x.id }], targetFolderId: y.id }).then((r) => r.status),
      A.post('/api/items/move', { items: [{ type: 'folder', id: y.id }], targetFolderId: x.id }).then((r) => r.status),
    ])
    res.push(st.join('/'))
  }
  const tree = await A.get('/api/folders/tree')
  out.C5_crossMove = { statuses: tally(res), treeStatus: tree.status }
}

// C6. 업로드 도중 탈퇴
{
  const A = await new Client().signup()
  const big = Buffer.alloc(40 * 1024 * 1024, 5)
  const up = A.upload(A.me.rootFolderId, 'c6.bin', big).then((r) => r.status)
  await new Promise((r) => setTimeout(r, 400))
  const del = await A.post('/api/users/me/delete', { password: PASSWORD })
  out.C6_uploadVsAccountDeletion = { upload: await up, deletion: del.status }
}

// C7. 업로드 도중 팀에서 내보내기 / 팀 삭제
{
  const A = await new Client().signup()
  const B = await new Client().signup()
  const t = await A.createTeam('c7')
  const m = await A.addMember(t.id, B, { canEdit: true, canDelete: true, canInvite: false })
  const big = Buffer.alloc(40 * 1024 * 1024, 6)
  const up = B.upload(t.rootFolderId, 'c7.bin', big).then((r) => r.status)
  await new Promise((r) => setTimeout(r, 400))
  const kick = await A.del(`/api/teams/${t.id}/members/${m.memberId}`)
  const t2 = await A.createTeam('c7b')
  const up2 = A.upload(t2.rootFolderId, 'c7b.bin', big).then((r) => r.status)
  await new Promise((r) => setTimeout(r, 400))
  const tdel = await A.del(`/api/teams/${t2.id}`)
  out.C7_uploadVsMembership = { upload: await up, kick: kick.status, uploadIntoDeletedTeam: await up2, teamDelete: tdel.status }
}

// C8. 같은 초대를 동시에 수락·거절
{
  const res = []
  for (let i = 0; i < 6; i++) {
    const A = await new Client().signup()
    const B = await new Client().signup()
    const t = await A.createTeam(`c8-${i}`)
    await A.post(`/api/teams/${t.id}/invitations`, { username: B.username })
    const inv = (await B.get('/api/notifications')).data.items.find((n) => n.invitationId).invitationId
    const [a, r] = await Promise.all([B.post(`/api/invitations/${inv}/accept`).then((x) => x.status), B.post(`/api/invitations/${inv}/reject`).then((x) => x.status)])
    const member = (await A.get(`/api/teams/${t.id}`)).data.members.some((x) => x.username === B.username)
    const status = (await B.get('/api/notifications')).data.items.find((n) => n.invitationId === inv)?.invitationStatus
    res.push(`수락${a}/거절${r}→멤버${member},초대${status}`)
  }
  out.C8_acceptVsReject = tally(res)
}

// C9. 다운로드 1회 제한 링크를 10명이 동시에
{
  const A = await new Client().signup()
  const f = (await A.upload(A.me.rootFolderId, 'c9.txt', 'x', 'text/plain')).data
  const link = (await A.post(`/api/files/${f.id}/share-links`, { downloadLimit: 1 })).data
  const st = await Promise.all(Array.from({ length: 10 }, () => new Client().get(`/api/public/shares/${link.token}/download`).then((r) => r.status)))
  out.C9_downloadLimit = tally(st)
}

// C10. 휴지통 비우기 동시 3번 + 복원
{
  const A = await new Client().signup()
  const ids = []
  for (let i = 0; i < 20; i++) ids.push((await A.upload(A.me.rootFolderId, `t${i}.txt`, `t${i}`, 'text/plain')).data.id)
  await A.post('/api/items/delete', { items: ids.map((id) => ({ type: 'file', id })) })
  const st = await Promise.all([A.del('/api/trash'), A.del('/api/trash'), A.del('/api/trash'), A.post(`/api/trash/${ids[0]}/restore`)].map((p) => p.then((r) => r.status)))
  const usage = (await A.get('/api/files/usage')).data
  out.C10_emptyTrashRace = { statuses: st, usage }
}

out.integrity = integrity()
writeFileSync(new URL('../results/concurrency.json', import.meta.url), JSON.stringify(out, null, 2))
console.log(JSON.stringify(out, null, 2))
