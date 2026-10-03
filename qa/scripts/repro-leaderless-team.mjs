// [QA] 팀장 위임과 그 멤버의 나가기가 동시에 성공하면 팀장 없는 팀이 생기는지 재현하고, 그 뒤 영향을 확인합니다.
// 실행: node qa/scripts/repro-leaderless-team.mjs
import { Client, PASSWORD } from './lib.mjs'

// 경합 창을 넓히려고 DB 왕복마다 지연을 넣습니다(QA 스택의 Toxiproxy). QA_DB_LATENCY_MS=0 이면 넣지 않음.
const latency = Number(process.env.QA_DB_LATENCY_MS ?? 20)
const toxi = (path, method, body) => fetch('http://localhost:18474' + path, { method, headers: { 'content-type': 'application/json' }, body: body && JSON.stringify(body) })
if (latency) await toxi('/proxies/mysql/toxics', 'POST', { name: 'repro', type: 'latency', stream: 'downstream', attributes: { latency } })
process.on('exit', () => {})
const cleanup = async () => { if (latency) await toxi('/proxies/mysql/toxics/repro', 'DELETE') }

for (let round = 1; round <= 60; round++) {
  const A = await new Client('A').signup()
  const C = await new Client('C').signup()
  const t = await A.createTeam(`leaderless-${round}`)
  const m = await A.addMember(t.id, C, { canEdit: true, canDelete: true, canInvite: true })
  const [d, l] = await Promise.all([A.post(`/api/teams/${t.id}/leader/${m.memberId}`), C.post(`/api/teams/${t.id}/leave`)])
  if (d.status !== 204 || l.status !== 204) continue
  const detail = (await A.get(`/api/teams/${t.id}`)).data
  console.log(`round ${round}: 위임 ${d.status}, 나가기 ${l.status}`)
  console.log('팀 멤버', JSON.stringify(detail.members.map((x) => ({ user: x.username === A.username ? 'A' : x.username, leader: x.leader }))))
  console.log('A 의 내 권한', JSON.stringify(detail.myPermissions))
  console.log('C 가 팀 조회', (await C.get(`/api/teams/${t.id}`)).status)
  console.log('A 가 팀 삭제', (await A.del(`/api/teams/${t.id}`)).status)
  console.log('A 가 팀 초대', (await A.post(`/api/teams/${t.id}/invitations`, { username: C.username })).status)
  console.log('A 가 나가기', (await A.post(`/api/teams/${t.id}/leave`)).status)
  const del = await C.post('/api/users/me/delete', { password: PASSWORD })
  console.log('C 탈퇴', del.status, del.data?.detail)
  console.log('C 의 팀 목록(GET /api/teams)', JSON.stringify((await C.get('/api/teams')).data.map((x) => x.name)))
  const cap = []
  for (let i = 0; i < 10; i++) cap.push((await C.post('/api/teams', { name: `c-cap-${i}` })).status)
  console.log('C 가 새 팀 10개 만들기', JSON.stringify(cap))
  await cleanup()
  process.exit(0)
}
await cleanup()
console.log('60회 안에 재현되지 않음')
