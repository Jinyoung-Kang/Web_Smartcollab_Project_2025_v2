// [6차 QA S3] 접근 코드 잠금(ADR-0013) 우회 시도 — 잠금을 켠 로컬 QA 스택에만
// 사용: QA_GATE_CODE=<시험용 코드> node qa/scripts/gate-bypass.mjs [결과 디렉터리]
// 경로를 정규화하지 않도록 node:http 로 날것 그대로 보냅니다(fetch 는 .. 를 지움).
import { mkdirSync, writeFileSync } from 'node:fs'
import http from 'node:http'
import { BASE, table } from './lib.mjs'

const OUT = process.argv[2] ?? 'qa/results/qa-2026-10-13'
const u = new URL(BASE)
const rows = []
const check = (name, ok, detail = '') => rows.push({ ok: ok ? 'OK' : 'FAIL', name, detail })

function raw(method, path, headers = {}, body) {
  return new Promise((resolve) => {
    // 브라우저 폼처럼 길이를 알림(길이 없는 chunked 본문은 잠금 해제가 413 으로 거절 — 설계)
    if (body) headers = { ...headers, 'content-length': Buffer.byteLength(body) }
    const req = http.request({ host: u.hostname, port: u.port, method, path, headers }, (res) => {
      const chunks = []
      res.on('data', (c) => chunks.push(c))
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, text: Buffer.concat(chunks).toString('utf8') }))
    })
    req.on('upgrade', (res, socket) => { socket.destroy(); resolve({ status: 101, headers: res.headers, text: '' }) })
    req.on('error', (e) => resolve({ status: e.code, headers: {}, text: '' }))
    if (body) req.write(body)
    req.end()
  })
}

// 앱 데이터가 새어 나왔는지: API JSON(세션·설정)·앱 화면(HTML 의 앱 스크립트)·API 문서
const leaked = (r) => /"authenticated"|"aiEnabled"|"openapi"|"items"|<script type="module"|\/assets\/index-/.test(r.text)

// 1) 잠금을 풀지 않은 요청의 경로 변형
const paths = [
  '/api/auth/session', '/api/public/config', '/', '/index.html', '/drive', '/assets/', '/favicon.svg',
  '/actuator/health/../../api/auth/session', '/actuator/health/..%2f..%2fapi/auth/session', '/actuator/health/%2e%2e/%2e%2e/api/auth/session',
  '/actuator/health;/../../api/auth/session', '/actuator/health/x;y', '/actuator/health/liveness/../../../api/public/config',
  '/actuator/health%2f..%2f..%2fapi%2fpublic%2fconfig', '/actuator/health/%252e%252e/api/public/config',
  '//api/public/config', '/API/public/config', '/api/public/config;jsessionid=x', '/api/./public/config', '/api/public//config',
  '/__gate/../api/public/config', '/__gate/%2e%2e/api/public/config', '/__gate/gate.css/../../api/public/config', '/__gate//../api/public/config',
  '/actuator', '/actuator/info', '/actuator/env', '/actuator/health/../env', '/v3/api-docs', '/swagger-ui.html', '/error',
  '/s/abc', '/api/public/shares/abc', '/ws',
]
for (const p of paths) {
  const r = await raw('GET', p, { accept: 'application/json' })
  const ok = !leaked(r) && !(r.status >= 200 && r.status < 300 && !/^\/actuator\/health/.test(p))
  check(`GET ${p}`, ok || (r.status === 200 && /^\/actuator\/health(\/(liveness|readiness))?$/.test(p)), `${r.status}`)
}
for (const m of ['HEAD', 'OPTIONS', 'TRACE', 'PUT', 'PATCH', 'DELETE', 'POST']) {
  const r = await raw(m, '/api/public/config', { origin: BASE })
  check(`${m} /api/public/config`, !(r.status >= 200 && r.status < 300) && !leaked(r), `${r.status}`)
}
const up = await raw('GET', '/ws', { connection: 'Upgrade', upgrade: 'websocket', 'sec-websocket-version': '13', 'sec-websocket-key': 'dGhlIHNhbXBsZSBub25jZQ==', origin: BASE })
check('WebSocket 업그레이드(쿠키 없음)', up.status !== 101, `${up.status}`)

// 2) 쿠키 위조
for (const [label, value] of [['임의 값', 'abc'], ['빈 값', ''], ['점 셋', 'a.b.c'], ['긴 값', 'x'.repeat(5000)]]) {
  const r = await raw('GET', '/api/public/config', { cookie: `__Host-sc_gate=${value}; sc_gate=${value}` })
  check(`쿠키 위조(${label})`, [400, 401].includes(r.status) && !leaked(r), `${r.status}`)  // 400: 헤더가 Tomcat 한도(8KB)를 넘음
}

// 3) 잠금 해제 — 다른 출처·틀린 코드·시도 제한·다음 주소(열린 리디렉션)·잠금화면 XSS
const form = (code, next) => `code=${encodeURIComponent(code)}&next=${encodeURIComponent(next)}`
const post = (body, headers = {}) => raw('POST', '/__gate/unlock', { 'content-type': 'application/x-www-form-urlencoded', origin: BASE, ...headers, 'x-forwarded-for': headers['x-forwarded-for'] ?? '198.51.100.7' }, body)
const cross = await post(form(process.env.QA_GATE_CODE, '/'), { origin: 'https://evil.example' })
check('다른 출처의 잠금 해제', cross.status === 403, `${cross.status}`)
const good = await post(form(process.env.QA_GATE_CODE, '/drive'), { 'x-forwarded-for': '198.51.100.9' })
const setCookie = String(good.headers['set-cookie'] ?? '')
// 로컬(http, COOKIE_SECURE=false)은 sc_gate, 운영(https)은 __Host-sc_gate — 운영 쪽은 운영 익명 점검에서 확인
check('맞는 코드 → 303 + 잠금 쿠키(HttpOnly·SameSite=Lax)', good.status === 303 && /sc_gate=/.test(setCookie) && /HttpOnly/i.test(setCookie) && /SameSite=Lax/i.test(setCookie), `${good.status} ${good.headers.location}`)
if (good.status !== 303) { console.log('unlock failed', good.status, good.text.slice(0, 300)); process.exit(1) }
const cookie = setCookie.split(';')[0]
const opened = await raw('GET', '/api/public/config', { cookie })
check('받은 쿠키로 API', opened.status === 200, `${opened.status}`)
const [name, value] = cookie.split('=')
const tampered = value.slice(0, -2) + (value.at(-2) === 'A' ? 'B' : 'A') + value.at(-1)
const t = await raw('GET', '/api/public/config', { cookie: `${name}=${tampered}` })
check('서명 한 글자 바꾼 쿠키', t.status === 401, `${t.status}`)
const parts = value.split('.')
if (parts.length >= 2) {
  const swapped = await raw('GET', '/api/public/config', { cookie: `${name}=${parts.slice(0, -1).join('.').replace(/^[^.]+/, 'Zm9v')}.${parts.at(-1)}` })
  check('코드 이름을 바꾼 쿠키', swapped.status === 401, `${swapped.status}`)
}
for (const next of ['//evil.example', '/\\evil.example', '/%5Cevil.example', 'https://evil.example', '/\t/evil.example', '/%09/evil.example', '/%2F%2Fevil.example', '/__gate/lock', 'javascript:alert(1)', '/ /evil.example']) {
  const r = await post(form(process.env.QA_GATE_CODE, next), { 'x-forwarded-for': '198.51.100.10' })
  const loc = String(r.headers.location ?? '')
  const resolved = loc ? new URL(loc, BASE) : null
  check(`다음 주소 ${JSON.stringify(next)}`, r.status === 303 && resolved?.origin === u.origin, `${r.status} → ${loc}`)
}
const xss = await raw('GET', '/__gate/?next=' + encodeURIComponent('/"><script>alert(1)</script><x a="'), { accept: 'text/html' })
check('잠금화면 next 반사 XSS', !/<script>alert\(1\)<\/script>/.test(xss.text), `${xss.status}`)
check('잠금화면 CSP', /default-src 'none'/.test(String(xss.headers['content-security-policy'] ?? '')), String(xss.headers['content-security-policy'] ?? '').slice(0, 80))
let last
for (let i = 0; i < 7; i++) last = await post(form('wrong-code-' + i, '/'), { 'x-forwarded-for': '198.51.100.20' })
check('틀린 코드 7번 → 429', last.status === 429, `${last.status} retry-after=${last.headers['retry-after'] ?? ''}`)
const spoof = await post(form('wrong-code-x', '/'), { 'x-forwarded-for': '198.51.100.21, 198.51.100.20' })
check('X-Forwarded-For 앞쪽 위조로 잠금 우회 시도', spoof.status === 401 || spoof.status === 429, `${spoof.status}`)

mkdirSync(OUT, { recursive: true })
const fails = rows.filter((r) => r.ok !== 'OK')
writeFileSync(`${OUT}/gate-bypass.md`, `# 접근 코드 잠금 우회 점검 (${new Date().toISOString()})\n\n${rows.length}건 중 기대와 다름 ${fails.length}건\n\n${table(rows, ['ok', 'name', 'detail'])}\n`)
console.log(`checks=${rows.length} fail=${fails.length}`)
for (const f of fails) console.log('FAIL', f.name, f.detail)
