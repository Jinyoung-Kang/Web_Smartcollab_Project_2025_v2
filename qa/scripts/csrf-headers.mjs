// CSRF — 쿠키로 인증된 모든 상태 변경 요청이 토큰 없음·틀린 토큰·다른 사용자 토큰에서 거절되는지(부수 효과 없음 포함)
// 보안 헤더 — 화면·API·정적 파일·파일 응답의 헤더
// 실행: node qa/scripts/csrf-headers.mjs
import { writeFileSync } from 'node:fs'
import { BASE, Client, table } from './lib.mjs'

const U = await new Client('csrf').signup()
const W = await new Client('csrf-other').signup()
const root = U.me.rootFolderId
const folder = await U.createFolder(root, 'keep')
const file = (await U.upload(root, 'keep.txt', 'keep', 'text/plain')).data
const version = (await U.get(`/api/files/${file.id}/versions`)).data[0].versionId
const team = await U.createTeam('csrf-team')
const link = (await U.post(`/api/files/${file.id}/share-links`, {})).data

const M = [
  ['POST', '/api/folders', { parentId: folder.id, name: 'x' }],
  ['PATCH', `/api/folders/${folder.id}`, { name: 'x' }],
  ['DELETE', `/api/folders/${folder.id}`],
  ['PATCH', `/api/files/${file.id}`, { name: 'x.txt' }],
  ['DELETE', `/api/files/${file.id}`],
  ['PUT', `/api/files/${file.id}/content`, { content: 'x', baseVersionId: version }],
  ['POST', `/api/files/${file.id}/versions/${version}/restore`],
  ['POST', `/api/files/${file.id}/signatures`],
  ['POST', `/api/files/${file.id}/share-links`, {}],
  ['DELETE', `/api/share-links/${link.id}`],
  ['POST', '/api/items/move', { items: [{ type: 'file', id: file.id }], targetFolderId: folder.id }],
  ['POST', '/api/items/copy', { items: [{ type: 'file', id: file.id }], targetFolderId: folder.id }],
  ['POST', '/api/items/delete', { items: [{ type: 'file', id: file.id }] }],
  ['DELETE', '/api/trash'],
  ['POST', '/api/teams', { name: 'x' }],
  ['DELETE', `/api/teams/${team.id}`],
  ['POST', `/api/teams/${team.id}/invitations`, { username: W.username }],
  ['POST', `/api/teams/${team.id}/messages`, { content: 'x' }],
  ['DELETE', `/api/teams/${team.id}/messages`],
  ['POST', '/api/notifications/read-all'],
  ['DELETE', '/api/notifications'],
  ['POST', `/api/files/${file.id}/summary`],
  ['POST', '/api/auth/logout'],
  ['POST', '/api/users/me/delete', { password: 'QaPassw0rd!' }],
]
const rows = []
const otherToken = await W.fetchCsrf()
for (const [method, path, json] of M) {
  for (const [variant, token] of [['토큰 없음', null], ['틀린 토큰', 'deadbeef-0000'], ['다른 사용자 토큰', otherToken]]) {
    const h = { cookie: `SC_AUTH=${U.cookies.get('SC_AUTH')}` + (token ? `; XSRF-TOKEN=${U.cookies.get('XSRF-TOKEN')}` : '') }
    if (json) h['content-type'] = 'application/json'
    if (token) h['X-XSRF-TOKEN'] = token
    const r = await fetch(BASE + path, { method, headers: h, body: json ? JSON.stringify(json) : undefined })
    const body = await r.text()
    rows.push({ ok: r.status === 403 ? 'PASS' : 'FAIL', req: `${method} ${path}`, variant, status: r.status, code: (body.match(/"code":"(\w+)"/) ?? [])[1] ?? '' })
  }
}
// 부수 효과 없음 — 모든 대상이 그대로
const still = [
  (await U.get(`/api/files/${file.id}`)).data?.name === 'keep.txt',
  (await U.get(`/api/folders/${folder.id}`)).data?.folder?.name === 'keep',
  (await U.get(`/api/teams/${team.id}`)).status === 200,
  (await U.get('/api/auth/me')).status === 200,
  (await U.get(`/api/files/${file.id}/content`)).data?.content === 'keep',
]

// 보안 헤더
const pages = ['/', '/login', '/drive', '/share/abc', '/api/public/config', '/api/auth/me', `/api/files/${file.id}/download`, `/api/files/${file.id}/view`, '/actuator/health', '/favicon.svg', '/does-not-exist.js']
const assets = (await (await fetch(BASE + '/')).text()).match(/\/assets\/[^"]+\.js/g) ?? []
if (assets[0]) pages.push(assets[0])
const H = ['content-security-policy', 'x-content-type-options', 'x-frame-options', 'referrer-policy', 'permissions-policy', 'strict-transport-security', 'cache-control', 'server', 'x-powered-by', 'cross-origin-opener-policy', 'cross-origin-resource-policy']
const headerRows = []
for (const p of pages) {
  const r = await fetch(BASE + p, { headers: { cookie: U.cookieHeader() } })
  await r.arrayBuffer()
  const row = { path: p, status: r.status }
  for (const k of H) row[k] = (r.headers.get(k) ?? '—').slice(0, 70)
  headerRows.push(row)
}

const fails = rows.filter((r) => r.ok === 'FAIL')
const out = `# CSRF·보안 헤더 (${new Date().toISOString()})\n\nCSRF 요청 ${rows.length}건 중 403 아님 ${fails.length}건, 부수 효과 없음 ${still.every(Boolean)}\n\n${table(rows, ['ok', 'req', 'variant', 'status', 'code'])}\n\n## 보안 헤더\n\n${table(headerRows, ['path', 'status', ...H])}\n`
writeFileSync(new URL('../results/csrf-headers.md', import.meta.url), out)
console.log(`csrf=${rows.length} fail=${fails.length} noSideEffect=${still.every(Boolean)} ${JSON.stringify(still)}`)
for (const f of fails) console.log('FAIL', f.req, f.variant, f.status, f.code)
console.log(table(headerRows, ['path', 'status', ...H]))
