// 백업·복원 검증용 데이터 만들기(create)와 복원 뒤 비교(verify). backup-restore.sh 가 부릅니다.
// create <before.json>: 사용자·폴더·파일(무작위 바이트)·텍스트 버전 3개·팀 파일·휴지통 파일을 만들고 다운로드 SHA-256 을 기록
// verify <before.json> <after.json>: 같은 계정으로 로그인해 모든 파일의 다운로드 SHA-256·버전 수·휴지통을 비교
import { createHash, randomBytes } from 'node:crypto'
import { readFileSync, writeFileSync } from 'node:fs'
import { Client } from './lib.mjs'

const [mode, beforePath, afterPath] = process.argv.slice(2)
const sha = (buf) => createHash('sha256').update(buf).digest('hex')

async function snapshot(c, files) {
  const out = {}
  for (const f of files) {
    const d = await c.get(`/api/files/${f.id}/download`)
    const v = await c.get(`/api/files/${f.id}/versions`)
    out[f.id] = { name: f.name, status: d.status, sha256: d.status === 200 ? sha(d.buf) : null, versions: Array.isArray(v.data) ? v.data.length : null }
  }
  return out
}

if (mode === 'create') {
  const c = await new Client('backup').signup()
  const root = c.me.rootFolderId
  const folder = await c.createFolder(root, '백업 폴더')
  const files = []
  for (const [i, size] of [1024, 64 * 1024, 2 * 1024 * 1024, 7 * 1024 * 1024].entries()) {
    files.push((await c.upload(i % 2 ? folder.id : root, `파일-${i}.bin`, randomBytes(size))).data)
  }
  const text = (await c.upload(root, '문서.txt', '첫 버전', 'text/plain')).data
  for (const content of ['두 번째 버전', '세 번째 버전']) {
    const cur = await c.get(`/api/files/${text.id}/content`)
    await c.put(`/api/files/${text.id}/content`, { content, baseVersionId: cur.data.versionId })
  }
  files.push(text)
  const team = await c.createTeam('백업 팀')
  files.push((await c.upload(team.rootFolderId, '팀 파일.bin', randomBytes(300 * 1024))).data)
  const trashed = (await c.upload(root, '휴지통 파일.txt', '지울 파일', 'text/plain')).data
  await c.del(`/api/files/${trashed.id}`)
  const snap = await snapshot(c, files)
  writeFileSync(beforePath, JSON.stringify({ username: c.username, files, trashedId: trashed.id, teamId: team.id, snapshot: snap, usage: (await c.get('/api/files/usage')).data }, null, 2))
  console.log(`created ${files.length} files`)
} else if (mode === 'verify') {
  const before = JSON.parse(readFileSync(beforePath))
  const c = new Client('restored')
  const login = await c.login(before.username)
  const snap = await snapshot(c, before.files)
  const diffs = Object.entries(before.snapshot).filter(([id, b]) => JSON.stringify(b) !== JSON.stringify(snap[id]))
  const trash = (await c.get('/api/trash')).data
  const result = {
    login: login.status,
    files: before.files.length,
    mismatched: diffs.map(([id, b]) => ({ id, before: b, after: snap[id] })),
    trashedStillInTrash: Array.isArray(trash) && trash.some((t) => t.id === before.trashedId),
    teamVisible: (await c.get(`/api/teams/${before.teamId}`)).status,
    usageBefore: before.usage,
    usageAfter: (await c.get('/api/files/usage')).data,
  }
  writeFileSync(afterPath, JSON.stringify(result, null, 2))
  console.log(JSON.stringify(result, null, 2))
  if (login.status !== 200 || diffs.length) process.exit(1)
}
