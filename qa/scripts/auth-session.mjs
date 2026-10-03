// 인증·세션 점검 — 탈퇴한 사용자의 토큰, 변조 토큰, 쿠키 속성, 아이디 대소문자, 로그인 오류 응답 차이
// 실행: node qa/scripts/auth-session.mjs
import { writeFileSync } from 'node:fs'
import { BASE, Client, PASSWORD, table, uid } from './lib.mjs'

const rows = []
const rec = (name, ok, detail) => rows.push({ ok: ok ? 'PASS' : 'FAIL', name, detail })

// 1) 쿠키 속성
{
  const c = new Client()
  const username = `qa_${uid()}`
  await c.fetchCsrf()
  const res = await fetch(`${BASE}/api/auth/signup`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', cookie: c.cookieHeader(), 'X-XSRF-TOKEN': c.cookies.get('XSRF-TOKEN') },
    body: JSON.stringify({ username, password: PASSWORD, passwordConfirm: PASSWORD, name: 'n' }),
  })
  const auth = res.headers.getSetCookie().find((x) => x.startsWith('SC_AUTH='))
  rec('SC_AUTH HttpOnly', /HttpOnly/i.test(auth), auth.replace(/=[^;]+/, '=…'))
  rec('SC_AUTH SameSite=Strict', /SameSite=Strict/i.test(auth), '')
  rec('SC_AUTH Path=/', /Path=\//i.test(auth), '')
}

// 2) 탈퇴한 사용자의 쿠키로 요청 — 모두 401 이어야 하고 500 이 없어야 함
{
  const e = await new Client('E').signup()
  const saved = new Map(e.cookies)
  const root = e.me.rootFolderId
  const del = await e.post('/api/users/me/delete', { password: PASSWORD })
  rec('탈퇴 요청', del.status === 204 || del.status === 200, `status ${del.status}`)
  const ghost = new Client('ghost')
  ghost.cookies = saved
  const probes = [
    ['GET', '/api/auth/me'],
    ['GET', '/api/teams'],
    ['GET', '/api/notifications'],
    ['GET', '/api/files/usage'],
    ['GET', '/api/trash'],
    ['GET', '/api/folders/tree'],
    ['GET', `/api/folders/${root}`],
    ['POST', '/api/teams', { name: 'ghost team' }],
    ['POST', '/api/folders', { parentId: root, name: 'ghost' }],
    ['POST', '/api/notifications/read-all'],
    ['DELETE', '/api/notifications'],
    ['POST', '/api/users/me/delete', { password: PASSWORD }],
  ]
  for (const [m, p, json] of probes) {
    const r = await ghost.req(m, p, { json })
    rec(`탈퇴한 사용자 토큰 ${m} ${p}`, r.status === 401, `status ${r.status} ${r.status >= 500 ? r.text.slice(0, 120) : ''}`)
  }
  const up = await ghost.upload(root, 'ghost.txt', 'x')
  rec('탈퇴한 사용자 토큰 업로드', up.status === 401, `status ${up.status}`)
}

// 3) 변조 토큰
{
  const f = await new Client('F').signup()
  const token = f.cookies.get('SC_AUTH')
  const [h, p, s] = token.split('.')
  const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url')
  const payload = JSON.parse(Buffer.from(p, 'base64url').toString())
  const forged = {
    'alg=none': `${b64({ alg: 'none', typ: 'JWT' })}.${b64({ ...payload, sub: '1' })}.`,
    '서명 그대로 sub 변경': `${h}.${b64({ ...payload, sub: '1' })}.${s}`,
    '서명 제거': `${h}.${p}.`,
    '만료 연장(서명 그대로)': `${h}.${b64({ ...payload, exp: payload.exp + 86400 * 365 })}.${s}`,
    'HS256→HS512 헤더': `${b64({ alg: 'HS512' })}.${p}.${s}`,
    '쓰레기 값': 'not-a-jwt',
    '아주 긴 값(8KB)': 'a'.repeat(8192),
  }
  for (const [name, t] of Object.entries(forged)) {
    for (const via of ['cookie', 'bearer']) {
      const headers = via === 'bearer' ? { authorization: `Bearer ${t}` } : { cookie: `SC_AUTH=${t}` }
      const r = await fetch(`${BASE}/api/auth/me`, { headers })
      // 8KB 값은 Tomcat 요청 헤더 한도에 걸려 인증 전에 400 으로 거절됩니다(기대 동작)
      const expected = name.startsWith('아주 긴 값') ? [400, 431] : [401]
      rec(`변조 토큰 ${name} (${via})`, expected.includes(r.status), `status ${r.status}`)
    }
  }
  // 쿠키는 정상, Bearer 는 변조 — 어느 쪽으로도 인증되지 않거나 401
  const r = await fetch(`${BASE}/api/auth/me`, { headers: { cookie: `SC_AUTH=${token}`, authorization: 'Bearer not-a-jwt' } })
  rec('정상 쿠키 + 변조 Bearer', r.status === 401 || r.status === 200, `status ${r.status} (참고: 우선순위 확인)`)
  // 쿠키 인증인데 CSRF 헤더 없이 상태 변경 — 403
  const nocsrf = await fetch(`${BASE}/api/folders`, {
    method: 'POST',
    headers: { cookie: `SC_AUTH=${token}`, 'content-type': 'application/json' },
    body: JSON.stringify({ parentId: f.me.rootFolderId, name: 'nocsrf' }),
  })
  rec('쿠키 인증 + CSRF 없음 → 403', nocsrf.status === 403, `status ${nocsrf.status}`)
  // 쿠키 인증 + 엉터리 Bearer 로 CSRF 우회 시도 — 상태 변경이 일어나면 안 됨
  const bypass = await fetch(`${BASE}/api/folders`, {
    method: 'POST',
    headers: { cookie: `SC_AUTH=${token}`, authorization: 'Bearer x', 'content-type': 'application/json' },
    body: JSON.stringify({ parentId: f.me.rootFolderId, name: 'bypass' }),
  })
  rec('쿠키 인증 + 엉터리 Bearer + CSRF 없음 → 거절', bypass.status === 401 || bypass.status === 403, `status ${bypass.status}`)
  // Bearer 만으로는 CSRF 없이 동작(문서화된 API 클라이언트 방식)
  const bearer = await fetch(`${BASE}/api/folders`, {
    method: 'POST',
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
    body: JSON.stringify({ parentId: f.me.rootFolderId, name: 'bearer-ok' }),
  })
  rec('Bearer 인증은 CSRF 없이 허용(문서)', bearer.status === 201 || bearer.status === 200, `status ${bearer.status}`)
}

// 4) 아이디 대소문자 — 같은 아이디를 대소문자만 바꿔 다시 가입할 수 있나
{
  const base = `QaCase${uid()}`
  const g = await new Client('G').signup(base)
  const dup = new Client('G2')
  const r = await dup.post('/api/auth/signup', { username: base.toLowerCase(), password: PASSWORD, passwordConfirm: PASSWORD, name: 'dup' })
  rec('대소문자만 다른 아이디 가입 거절', r.status === 409, `status ${r.status} ${r.text.slice(0, 100)}`)
  const lg = await new Client().login(base.toLowerCase())
  rec('소문자로 로그인(참고)', true, `status ${lg.status}`)
  void g
}

// 5) 로그인 오류 응답 — 없는 아이디와 틀린 비밀번호가 같은 응답인지, 시간 차이
{
  const h = await new Client('H').signup()
  const t = async (u, p) => {
    const xs = []
    let body
    for (let i = 0; i < 15; i++) {
      const c = new Client()
      await c.fetchCsrf()
      const s = performance.now()
      const r = await c.post('/api/auth/login', { username: u, password: p })
      xs.push(performance.now() - s)
      body = `${r.status} ${r.data?.code} ${r.data?.detail}`
    }
    xs.sort((a, b) => a - b)
    return { median: xs[7], body }
  }
  const wrongPw = await t(h.username, 'WrongPass1')
  const noUser = await t(`nouser_${uid()}`, 'WrongPass1')
  rec('로그인 오류 본문 같음', wrongPw.body === noUser.body, `${wrongPw.body} / ${noUser.body}`)
  const ratio = wrongPw.median / noUser.median
  rec('로그인 응답 시간 차이(중앙값) 2배 이내', ratio < 2 && ratio > 0.5, `틀린 비밀번호 ${wrongPw.median.toFixed(1)}ms / 없는 아이디 ${noUser.median.toFixed(1)}ms`)
}

const fails = rows.filter((r) => r.ok === 'FAIL')
writeFileSync(new URL('../results/auth-session.md', import.meta.url), `# 인증·세션 점검 (${new Date().toISOString()})\n\n${table(rows, ['ok', 'name', 'detail'])}\n`)
console.log(`checks=${rows.length} fail=${fails.length}`)
for (const f of fails) console.log('FAIL', f.name, f.detail)
