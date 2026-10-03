// 팀장 없는 팀(경합으로 생긴 상태)의 영향 확인 — 남은 멤버(A)와 나간 소유자(C)
// 사용: node qa/scripts/leaderless-impact.mjs <A 아이디> <C 아이디> <팀 ID>
import { Client, PASSWORD } from './lib.mjs'
const [ua, uc, teamId] = process.argv.slice(2)
const A = new Client('A'); await A.login(ua)
const C = new Client('C'); await C.login(uc)
const detail = (await A.get(`/api/teams/${teamId}`)).data
console.log('팀 멤버', JSON.stringify(detail.members.map((x) => ({ user: x.username === ua ? 'A' : x.username === uc ? 'C' : x.username, leader: x.leader }))), '소유자', detail.ownerUsername === uc ? 'C(나간 사용자)' : detail.ownerUsername)
console.log('A 의 내 권한', JSON.stringify(detail.myPermissions))
console.log('C 가 팀 조회', (await C.get(`/api/teams/${teamId}`)).status)
console.log('A 가 팀 초대', (await A.post(`/api/teams/${teamId}/invitations`, { username: uc })).status)
console.log('A 가 팀 삭제', (await A.del(`/api/teams/${teamId}`)).status)
console.log('A 가 위임(자기 자신에게)', (await A.post(`/api/teams/${teamId}/leader/${detail.members[0].memberId}`)).status)
const del = await C.post('/api/users/me/delete', { password: PASSWORD })
console.log('C 탈퇴', del.status, del.data?.detail)
console.log('C 의 팀 목록', JSON.stringify((await C.get('/api/teams')).data.map((x) => x.name)))
