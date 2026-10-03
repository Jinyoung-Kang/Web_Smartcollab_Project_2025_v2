// 입력 퍼징 — 모든 API 에 비정상 ID·쿼리·본문을 넣어 5xx, 내부 정보 노출, 오류 형식 불일치를 찾습니다.
// 실행: node qa/scripts/api-fuzz.mjs
import { writeFileSync } from 'node:fs'
import { Client, PASSWORD, table } from './lib.mjs'

const U = await new Client('fuzz').signup()
const V = await new Client('fuzz-member').signup()
const root = U.me.rootFolderId
const folder = await U.createFolder(root, 'fz')
const file = (await U.upload(root, 'fz.txt', 'hello fuzz', 'text/plain')).data
const version = (await U.get(`/api/files/${file.id}/versions`)).data[0].versionId
const link = (await U.post(`/api/files/${file.id}/share-links`, {})).data
const team = await U.createTeam('fz-team')
const member = await U.addMember(team.id, V, { canEdit: true, canDelete: true, canInvite: true })
const trashed = (await U.upload(root, 'tr.txt', 'x')).data
await U.del(`/api/files/${trashed.id}`)
const trashedFolder = await U.createFolder(root, 'trf')
await U.del(`/api/folders/${trashedFolder.id}`)
const notif = (await V.get('/api/notifications')).data.items[0]

const valid = {
  file: file.id, folder: folder.id, team: team.id, member: member.memberId, version, link: link.id,
  notif: notif?.id ?? 1, inv: notif?.invitationId ?? 1, token: link.token, trashed: trashed.id, trashedFolder: trashedFolder.id,
}

// [method, path 템플릿, 정상 본문, 사용자]
const E = [
  ['GET', '/api/auth/me'],
  ['POST', '/api/auth/login', { username: 'x', password: 'y' }, 'anon'],
  ['POST', '/api/auth/signup', { username: 'abcd', password: 'Passw0rd1', passwordConfirm: 'Passw0rd1', name: 'n', email: 'a@b.c' }, 'anon'],
  ['GET', '/api/folders/{folder}'],
  ['GET', '/api/folders/tree?teamId={team}'],
  ['POST', '/api/folders', { parentId: '{folder}', name: 'n' }],
  ['PATCH', '/api/folders/{folder}', { name: 'fz' }],
  ['GET', '/api/files/{file}'],
  ['GET', '/api/files/{file}/download'],
  ['GET', '/api/files/{file}/view'],
  ['GET', '/api/files/{file}/office-preview-url'],
  ['PATCH', '/api/files/{file}', { name: 'fz.txt' }],
  ['GET', '/api/files/{file}/content'],
  ['PUT', '/api/files/{file}/content', { content: 'c', baseVersionId: '{version}' }],
  ['GET', '/api/files/{file}/versions'],
  ['POST', '/api/files/{file}/versions/{version}/restore'],
  ['POST', '/api/files/{file}/signatures'],
  ['GET', '/api/files/search?q=fz&teamId={team}'],
  ['GET', '/api/files/usage?teamId={team}'],
  ['POST', '/api/items/move', { items: [{ type: 'file', id: '{file}' }], targetFolderId: '{folder}' }],
  ['POST', '/api/items/copy', { items: [{ type: 'file', id: '{file}' }], targetFolderId: '{folder}' }],
  ['POST', '/api/items/delete', { items: [{ type: 'file', id: '{trashed}' }] }],
  ['GET', '/api/trash?teamId={team}'],
  ['POST', '/api/trash/{trashed}/restore'],
  ['POST', '/api/trash/folders/{trashedFolder}/restore'],
  ['POST', '/api/files/{file}/summary'],
  ['POST', '/api/files/{file}/translation?target=EN'],
  ['GET', '/api/teams'],
  ['POST', '/api/teams', { name: 'n' }],
  ['GET', '/api/teams/{team}'],
  ['GET', '/api/teams/{team}/presence'],
  ['POST', '/api/teams/{team}/invitations', { username: 'nobody_x' }],
  ['PUT', '/api/teams/{team}/members/{member}/permissions', { canEdit: true, canDelete: true, canInvite: true }],
  ['GET', '/api/teams/{team}/messages?before=&size=30'],
  ['POST', '/api/teams/{team}/messages', { content: 'hi' }],
  ['GET', '/api/notifications'],
  ['POST', '/api/notifications/{notif}/read'],
  ['POST', '/api/invitations/{inv}/reject', undefined, 'member'],
  ['GET', '/api/files/{file}/share-links'],
  ['POST', '/api/files/{file}/share-links', { password: 'abcd', expiresInHours: 1, downloadLimit: 1 }],
  ['GET', '/api/public/shares/{token}', undefined, 'anon'],
  ['POST', '/api/public/shares/{token}/unlock', { password: 'x' }, 'anon'],
  ['GET', '/api/public/shares/{token}/download?grant=x', undefined, 'anon'],
  ['GET', '/api/public/config', undefined, 'anon'],
  ['DELETE', '/api/share-links/{link}'],
  ['DELETE', '/api/notifications/{notif}'],
  ['DELETE', '/api/trash/{trashed}'],
  ['DELETE', '/api/trash/folders/{trashedFolder}'],
  ['DELETE', '/api/teams/{team}/members/{member}'],
  ['POST', '/api/teams/{team}/leader/{member}'],
  ['POST', '/api/teams/{team}/leave', undefined, 'member'],
  ['DELETE', '/api/teams/{team}/messages'],
  ['DELETE', '/api/files/{file}'],
  ['DELETE', '/api/folders/{folder}'],
  ['POST', '/api/users/me/delete', { password: 'wrong' }],
]

const BAD_IDS = ['-1', '0', '9223372036854775807', '9223372036854775808', 'abc', '1.5', '1e3', '%20', '%00', "1'%20OR%20'1'%3D'1", '..%2F..%2Fetc%2Fpasswd', '%E2%80%AE1']
const BAD_QUERY = ['', '-1', '0', '9223372036854775808', 'abc', '%25', '_', '%5C', "'%20OR%201%3D1%20--", 'a'.repeat(5000), '%F0%9F%98%80'.repeat(10)]
const BAD_VALUES = ['str', 123, -1, 0, 9223372036854775808, 1.5, true, [], {}, null, 'x'.repeat(300), 'x'.repeat(1_000_000), '<script>alert(1)</script>', '\u0000', '../../etc/passwd', 'a\r\nSet-Cookie: x=1']

const LEAK = /(Exception|\bat [a-z]+\.[a-z0-9_.]+\(|org\.springframework|org\.hibernate|java\.lang|java\.sql|SQLState|jdbc:|com\.mysql|\/app\/|Whitelabel)/i
const clients = { user: U, member: V, anon: new Client('anon') }
const findings = []
let total = 0
const counts = {}

function fill(tpl, subst = {}) {
  return tpl.replace(/\{(\w+)\}/g, (_, k) => (k in subst ? subst[k] : valid[k]))
}
function fillBody(body, subst = {}) {
  if (body === undefined) return undefined
  return JSON.parse(JSON.stringify(body).replace(/"\{(\w+)\}"/g, (_, k) => JSON.stringify(k in subst ? subst[k] : valid[k])))
}

async function send(who, method, path, opts, label) {
  const r = await clients[who].req(method, path, opts)
  total++
  counts[r.status] = (counts[r.status] ?? 0) + 1
  const ct = r.headers.get('content-type') ?? ''
  const problems = []
  if (r.status >= 500 && !(r.status === 503 && r.data?.code === 'FEATURE_DISABLED')) problems.push('5xx')
  if (LEAK.test(r.text)) problems.push('내부 정보')
  if (r.status >= 400 && r.status !== 401 && path.startsWith('/api') && !ct.includes('problem+json')) problems.push(`오류 형식(${ct || '없음'})`)
  if (r.status >= 400 && ct.includes('problem+json') && !r.data?.code) problems.push('code 없음')
  if (problems.length) findings.push({ problems: problems.join(','), who, req: `${method} ${path.slice(0, 120)}`, label, status: r.status, body: r.text.slice(0, 200) })
  return r
}

for (const [method, tpl, body, who = 'user'] of E) {
  const keys = [...tpl.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).filter((k) => !tpl.includes(`=${'{' + k + '}'}`))
  // 1) 경로 ID 조작
  for (const k of keys) {
    for (const bad of BAD_IDS) await send(who, method, fill(tpl, { [k]: bad }), { json: fillBody(body) }, `path ${k}=${bad.slice(0, 20)}`)
  }
  // 2) 쿼리 값 조작
  const q = [...tpl.matchAll(/[?&](\w+)=([^&]*)/g)].map((m) => m[1])
  for (const name of q) {
    for (const bad of BAD_QUERY) {
      const p = fill(tpl).replace(new RegExp(`([?&]${name}=)[^&]*`), `$1${bad}`)
      await send(who, method, p, { json: fillBody(body) }, `query ${name}=${bad.slice(0, 20)}`)
    }
  }
  // 3) 본문 조작
  if (body !== undefined) {
    const p = fill(tpl)
    for (const raw of ['', 'null', '[]', '{', '"str"', '{"a":1}']) {
      await send(who, method, p, { body: raw, headers: { 'content-type': 'application/json' } }, `raw body ${raw || '(빈 본문)'}`)
    }
    await send(who, method, p, { body: JSON.stringify(fillBody(body)), headers: { 'content-type': 'text/plain' } }, 'content-type text/plain')
    await send(who, method, p, { body: '<a/>', headers: { 'content-type': 'application/xml' } }, 'content-type xml')
    const fields = Object.keys(body)
    for (const f of fields) {
      for (const v of BAD_VALUES) {
        const b = fillBody(body)
        b[f] = v
        let json = JSON.stringify(b)
        if (v === 9223372036854775808) json = json.replace(/9223372036854776000/, '9223372036854775808')
        await send(who, method, p, { body: json, headers: { 'content-type': 'application/json' } }, `${f}=${JSON.stringify(v)?.slice(0, 20)}`)
      }
    }
    // 배열 안 항목 조작(items)
    if (body.items) {
      for (const v of [[{ type: 'file' }], [{ id: 1 }], [{ type: 'zzz', id: 1 }], [null], [{ type: 'file', id: -1 }], Array.from({ length: 201 }, () => ({ type: 'file', id: valid.file }))]) {
        const b = fillBody(body)
        b.items = v
        await send(who, method, p, { json: b }, `items=${JSON.stringify(v).slice(0, 30)}`)
      }
    }
  }
  // 4) 지원하지 않는 메서드
  await send(who, method === 'GET' ? 'PATCH' : 'GET', fill(tpl), {}, 'wrong method')
}
// 5) 없는 경로
for (const p of ['/api/nope', '/api/files', '/api/files/', '/api//files/1', '/api/files/1/../../auth/me', '/actuator/env', '/actuator/heapdump', '/v3/api-docs', '/swagger-ui.html', '/.env', '/WEB-INF/web.xml']) {
  await send('user', 'GET', p, {}, 'path')
}

writeFileSync(
  new URL('../results/api-fuzz.md', import.meta.url),
  `# 입력 퍼징 결과 (${new Date().toISOString()})\n\n요청 ${total}건, 상태 코드 분포 ${JSON.stringify(counts)}\n\n문제 ${findings.length}건\n\n${table(findings, ['problems', 'who', 'req', 'label', 'status', 'body'])}\n`,
)
console.log(`requests=${total} status=${JSON.stringify(counts)} findings=${findings.length}`)
const groups = {}
for (const f of findings) {
  const key = `${f.problems} | ${f.req.replace(/\/\d+/g, '/N').slice(0, 80)} | ${f.status}`
  groups[key] = (groups[key] ?? 0) + 1
}
for (const [k, n] of Object.entries(groups)) console.log(n, k)
void PASSWORD
