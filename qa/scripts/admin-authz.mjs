// [6차 QA S1] 관리 전용 계정(ADR-0015) 블랙박스 점검 — 역할 분리·토큰 조작·입력 경계값·동시 삭제·삭제 뒤 세션
// 사용: ADMIN_USERNAME=… ADMIN_PASSWORD=… node qa/scripts/admin-authz.mjs [결과 디렉터리]
// (관리자 값은 QA 스택을 띄울 때 쓴 것과 같아야 합니다. 출력하지 않습니다)
import { mkdirSync, writeFileSync } from 'node:fs'
import http from 'node:http'
import { BASE, Client, table, uid } from './lib.mjs'

const OUT = process.argv[2] ?? 'qa/results/qa-2026-10-13'
const rows = []
const check = (name, ok, detail = '') => rows.push({ ok: ok ? 'OK' : 'FAIL', name, detail })
const is = (status, ...expected) => expected.includes(status)

const admin = new Client('admin')
const login = await admin.login(process.env.ADMIN_USERNAME, process.env.ADMIN_PASSWORD)
if (login.status !== 200) throw new Error(`관리자 로그인 ${login.status} — ADMIN_USERNAME·ADMIN_PASSWORD 를 확인하세요`)
check('관리자 로그인 → role=ADMIN, 드라이브 없음', admin.me.role === 'ADMIN' && admin.me.rootFolderId === undefined, JSON.stringify({ role: admin.me.role }))

const user = await new Client().signup()
const other = await new Client().signup()
const anon = new Client()

// 1) 역할 분리
for (const [who, c, expect] of [['비로그인', anon, [401]], ['일반 사용자', user, [403]]]) {
  const g = await c.get('/api/admin/users')
  check(`${who} GET /api/admin/users`, is(g.status, ...expect), g.status)
  const d = await c.del(`/api/admin/users/${other.me.id}`)
  check(`${who} DELETE /api/admin/users/{남}`, is(d.status, ...expect), d.status)
}
for (const [m, p, body] of [
  ['GET', '/api/folders/tree'], ['GET', `/api/folders/${user.me.rootFolderId}`], ['GET', '/api/teams'], ['POST', '/api/teams', { name: 'x' }],
  ['GET', '/api/notifications'], ['GET', '/api/files/search?q=a'], ['GET', '/api/trash'], ['GET', '/api/files/usage'],
  ['POST', '/api/users/me/delete', { password: 'x' }], ['GET', '/api/teams/1/messages'],
]) {
  const r = await admin.req(m, p, body ? { json: body } : {})
  check(`관리자 ${m} ${p}`, r.status === 403, r.status)
}
// WebSocket 핸드셰이크(HTTP 업그레이드) — fetch 는 Upgrade 헤더를 막아 node:http 로 보냄
const wsStatus = await new Promise((resolve) => {
  const u = new URL(BASE + '/ws')
  const r = http.request({ host: u.hostname, port: u.port, path: u.pathname, headers: {
    connection: 'Upgrade', upgrade: 'websocket', 'sec-websocket-version': '13', 'sec-websocket-key': 'dGhlIHNhbXBsZSBub25jZQ==',
    origin: BASE, cookie: admin.cookieHeader() } })
  r.on('upgrade', (res, socket) => { socket.destroy(); resolve(101) })
  r.on('response', (res) => { res.resume(); resolve(res.statusCode) })
  r.on('error', (e) => resolve(e.code))
  r.end()
})
const ws = { status: wsStatus }
check('관리자 /ws 업그레이드', ws.status === 403, ws.status)

// 2) 토큰 조작 — 서명을 그대로 두고 역할만 ADMIN 으로, alg=none
const jwt = user.cookies.get('SC_AUTH')
const [h, p, s] = jwt.split('.')
const payload = JSON.parse(Buffer.from(p, 'base64url').toString())
const forged = `${h}.${Buffer.from(JSON.stringify({ ...payload, role: 'ADMIN' })).toString('base64url')}.${s}`
const none = `${Buffer.from(JSON.stringify({ alg: 'none', typ: 'JWT' })).toString('base64url')}.${Buffer.from(JSON.stringify({ ...payload, role: 'ADMIN' })).toString('base64url')}.`
for (const [label, token] of [['역할만 바꾼 토큰', forged], ['alg=none 토큰', none]]) {
  const c = new Client(); c.cookies.set('SC_AUTH', token)
  const r = await c.get('/api/admin/users')
  check(`${label} → 관리 API`, r.status === 401, r.status)
  const b = await fetch(BASE + '/api/admin/users', { headers: { authorization: `Bearer ${token}` } })
  check(`${label}(Bearer) → 관리 API`, b.status === 401, b.status)
}

// 3) 입력 경계값 — 400·404 이고 500 이 아니어야 함
for (const q of ['page=-1', 'size=0', 'size=101', 'page=abc', 'size=1e9', 'page=2147483647', 'page=2147483647&size=100', 'page=99999999999', 'size=-5', 'page=1.5']) {
  const r = await admin.get(`/api/admin/users?${q}`)
  check(`GET /api/admin/users?${q}`, r.status < 500 && r.status !== 200 || (r.status === 200 && Array.isArray(r.data.items)), `${r.status} ${r.data?.code ?? ''}`)
}
for (const id of ['-1', '0', 'abc', '99999999999999999999', '1.5', '%00', '9223372036854775807']) {
  const r = await admin.del(`/api/admin/users/${id}`)
  check(`DELETE /api/admin/users/${id}`, [400, 404].includes(r.status), `${r.status} ${r.data?.code ?? ''}`)
}
const self = await admin.del(`/api/admin/users/${admin.me.id}`)
check('관리자 자기 자신 삭제', self.status === 404, self.status)

// 4) 응답에 민감 정보 없음
const list = await admin.get('/api/admin/users?size=100')
const text = list.text
check('목록에 비밀번호 해시·이메일·폴더 없음', !/\$2[aby]\$|password|email|rootFolderId/.test(text), list.status)

// 5) 같은 계정 동시 삭제 2회 + 삭제 중 그 계정의 업로드
const victim = await new Client().signup()
const [d1, d2, up] = await Promise.all([
  admin.del(`/api/admin/users/${victim.me.id}`),
  admin.del(`/api/admin/users/${victim.me.id}`),
  victim.upload(victim.me.rootFolderId, `race-${uid()}.txt`, 'x'.repeat(4096)),
])
check('동시 삭제 2회 → 한 번만 성공, 500 없음', [d1.status, d2.status].sort().join() === '200,404', `${d1.status}/${d2.status} 업로드 ${up.status}`)
const after = await victim.get('/api/auth/me')
check('삭제된 계정의 세션 → 401', after.status === 401, after.status)

// 6) 관리자가 지운 계정과 같은 아이디로 다시 가입 → 이전 데이터가 보이지 않음
const reborn = new Client()
const re = await reborn.post('/api/auth/signup', { username: victim.username, password: 'QaPassw0rd!', passwordConfirm: 'QaPassw0rd!', name: 'reborn' })
const tree = re.status === 201 ? await reborn.get('/api/folders/tree') : null
check('삭제된 아이디로 재가입 → 빈 드라이브', re.status === 201 && JSON.stringify(tree.data).indexOf('race-') < 0, `${re.status}`)

mkdirSync(OUT, { recursive: true })
const fails = rows.filter((r) => r.ok !== 'OK')
writeFileSync(`${OUT}/admin-authz.md`, `# 관리 전용 계정 점검 (${new Date().toISOString()})\n\n${rows.length}건 중 기대와 다름 ${fails.length}건\n\n${table(rows, ['ok', 'name', 'detail'])}\n`)
console.log(`checks=${rows.length} fail=${fails.length}`)
for (const f of fails) console.log('FAIL', f.name, f.detail)
