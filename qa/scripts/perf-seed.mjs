// 부하 측정용 시드 — 사용자 60명(팀 6개·채팅 50개씩), 사용자마다 파일 30개·텍스트 문서·10MB 파일, 큰 폴더(파일 1만 개) 1개
// 실행: node qa/scripts/perf-seed.mjs → qa/results/perf-users.json (QA 고정 비밀번호만 담김)
import { writeFileSync } from 'node:fs'
import { Client } from './lib.mjs'

const N = Number(process.env.PERF_USERS ?? 60)
const BIG = Number(process.env.PERF_BIG_FOLDER ?? 10000)
const pool = async (items, limit, fn) => {
  const out = []
  let i = 0
  await Promise.all(Array.from({ length: limit }, async () => {
    while (i < items.length) {
      const k = i++
      out[k] = await fn(items[k], k)
    }
  }))
  return out
}

const started = Date.now()
const users = await pool(Array.from({ length: N }, (_, i) => i), 8, async (i) => {
  const c = await new Client().signup(`perf_${String(i).padStart(3, '0')}_${Math.random().toString(36).slice(2, 5)}`)
  const root = c.me.rootFolderId
  const folder = await c.createFolder(root, '프로젝트')
  for (let k = 0; k < 30; k++) await c.upload(folder.id, `문서-${k}.txt`, `내용 ${k} `.repeat(100), 'text/plain')
  const text = (await c.upload(root, '메모.txt', '편집할 문서입니다. '.repeat(50), 'text/plain')).data
  const big = (await c.upload(root, 'big-10mb.bin', Buffer.alloc(10 * 1024 * 1024, i % 255))).data
  return { client: c, username: c.username, rootFolderId: root, folderId: folder.id, textFileId: text.id, bigFileId: big.id }
})
// 팀: 10명씩 6개, 채팅 50개
const teams = []
for (let t = 0; t < N / 10; t++) {
  const leader = users[t * 10]
  const team = await leader.client.createTeam(`부하 팀 ${t}`)
  for (let m = 1; m < 10; m++) await leader.client.addMember(team.id, users[t * 10 + m].client, { canEdit: true, canDelete: true, canInvite: false })
  for (let k = 0; k < 50; k++) await users[t * 10 + (k % 10)].client.post(`/api/teams/${team.id}/messages`, { content: `메시지 ${k} — 부하 측정용 채팅입니다.` })
  for (let m = 0; m < 10; m++) Object.assign(users[t * 10 + m], { teamId: team.id, teamRootFolderId: team.rootFolderId })
  teams.push(team.id)
}
// 큰 폴더(첫 사용자)
const owner = users[0]
const bigFolder = await owner.client.createFolder(owner.rootFolderId, `큰 폴더 ${BIG}`)
await pool(Array.from({ length: BIG }, (_, i) => i), 16, (i) => owner.client.upload(bigFolder.id, `항목-${String(i).padStart(5, '0')}.txt`, `${i}`, 'text/plain'))
owner.bigFolderId = bigFolder.id

writeFileSync(
  new URL('../results/perf-users.json', import.meta.url),
  JSON.stringify({ seededAt: new Date().toISOString(), seedSeconds: Math.round((Date.now() - started) / 1000), password: 'QaPassw0rd!', bigFolder: { ownerIndex: 0, folderId: bigFolder.id, items: BIG }, users: users.map(({ client, ...u }) => u) }, null, 2),
)
console.log(`users=${users.length} teams=${teams.length} bigFolder=${bigFolder.id} (${BIG}) ${Math.round((Date.now() - started) / 1000)}s`)
