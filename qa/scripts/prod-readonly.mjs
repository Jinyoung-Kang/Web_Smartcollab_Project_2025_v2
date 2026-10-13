// [6차 QA] 운영 앞단의 익명 읽기 전용 점검 — 로그인·쓰기·부하 없이 GET/HEAD 만 보냅니다(사용자 승인 범위).
// TLS·HTTP→HTTPS 전환·보안 헤더·잠금화면 응답·잠금 우회 경로·내부 정보 노출. 접근 코드·계정은 쓰지 않습니다.
// 사용: PROD_URL=https://<운영 주소> node qa/scripts/prod-readonly.mjs [결과 디렉터리]
// 운영 주소는 저장소에 남기지 않습니다 — 결과 파일에는 <운영>으로 가려 적습니다.
import { mkdirSync, writeFileSync } from 'node:fs'
import https from 'node:https'
import http from 'node:http'
import tls from 'node:tls'

const OUT = process.argv[2] ?? 'qa/results/qa-2026-10-13'
const base = new URL(process.env.PROD_URL ?? '')
if (base.protocol !== 'https:') throw new Error('PROD_URL=https://… 가 필요합니다')
const HOST = base.hostname
const mask = (s) => String(s).split(HOST).join('<운영>')
const rows = []
const check = (name, ok, detail = '') => rows.push({ ok: ok ? 'OK' : 'FAIL', name, detail: mask(detail) })
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

function raw(mod, method, path, headers = {}) {
  return new Promise((resolve) => {
    const req = mod.request({ host: HOST, port: mod === https ? 443 : 80, method, path, headers: { 'user-agent': 'smartcollab-qa-readonly', ...headers }, timeout: 20000 }, (res) => {
      const chunks = []
      res.on('data', (c) => { if (chunks.length < 64) chunks.push(c) })
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, text: Buffer.concat(chunks).toString('utf8') }))
    })
    req.on('timeout', () => { req.destroy(); resolve({ status: 'TIMEOUT', headers: {}, text: '' }) })
    req.on('error', (e) => resolve({ status: e.code, headers: {}, text: '' }))
    req.end()
  })
}
const get = async (path, headers) => { await sleep(150); return raw(https, 'GET', path, headers) }

// 1) TLS
const tlsInfo = await new Promise((resolve) => {
  const s = tls.connect({ host: HOST, port: 443, servername: HOST }, () => {
    const c = s.getPeerCertificate()
    resolve({ protocol: s.getProtocol(), cipher: s.getCipher().name, validTo: c.valid_to, authorized: s.authorized })
    s.end()
  })
  s.on('error', (e) => resolve({ error: e.code }))
})
check('TLS 1.2 이상·인증서 유효', /TLSv1\.[23]/.test(tlsInfo.protocol) && tlsInfo.authorized, JSON.stringify(tlsInfo))
for (const v of ['TLSv1', 'TLSv1.1']) {
  const r = await new Promise((resolve) => {
    const s = tls.connect({ host: HOST, port: 443, servername: HOST, minVersion: v, maxVersion: v }, () => { resolve('accepted'); s.end() })
    s.on('error', (e) => resolve(e.code ?? 'rejected'))
  })
  check(`옛 프로토콜 ${v} 거절`, r !== 'accepted', r)
}

// 2) HTTP → HTTPS
const plain = await raw(http, 'GET', '/', {})
check('HTTP 는 HTTPS 로 전환(301/308)', [301, 302, 307, 308].includes(plain.status) && String(plain.headers.location ?? '').startsWith('https://'), `${plain.status} → ${plain.headers.location ?? ''}`)

// 3) 잠금화면·보안 헤더
const page = await get('/', { accept: 'text/html' })
const h = page.headers
check('잠금화면(401 HTML)', page.status === 401 && /<html/i.test(page.text), `${page.status}`)
check('잠금화면 CSP', /default-src 'none'/.test(h['content-security-policy'] ?? ''), h['content-security-policy'] ?? '')
check('HSTS', /max-age=\d+/.test(h['strict-transport-security'] ?? ''), h['strict-transport-security'] ?? '(없음)')
check('nosniff', h['x-content-type-options'] === 'nosniff', h['x-content-type-options'] ?? '')
check('frame 차단(X-Frame-Options 또는 frame-ancestors)', /DENY|SAMEORIGIN/i.test(h['x-frame-options'] ?? '') || /frame-ancestors/.test(h['content-security-policy'] ?? ''), `${h['x-frame-options'] ?? ''}`)
check('Referrer-Policy', !!h['referrer-policy'], h['referrer-policy'] ?? '(없음)')
check('Cache-Control no-store(잠금화면)', /no-store/.test(h['cache-control'] ?? ''), h['cache-control'] ?? '')
check('서버 정보 노출 없음(Server·X-Powered-By)', !h['x-powered-by'] && !/tomcat|jetty|spring|kestrel|iis/i.test(h.server ?? ''), `server=${h.server ?? ''} x-powered-by=${h['x-powered-by'] ?? ''}`)
const api = await get('/api/public/config', { accept: 'application/json' })
check('API 는 401 GATE_LOCKED(problem+json)', api.status === 401 && /GATE_LOCKED/.test(api.text) && /problem\+json/.test(api.headers['content-type'] ?? ''), `${api.status} ${api.headers['content-type'] ?? ''}`)
const health = await get('/actuator/health', {})
check('헬스 체크 200(상세 없음)', health.status === 200 && !/components|diskSpace|db/.test(health.text), `${health.status} ${health.text.slice(0, 60)}`)

// 4) 잠금 우회 경로·내부 정보(모두 잠겨야 함)
const leaked = (r) => /"authenticated"|"aiEnabled"|"openapi"|"items"|<script type="module"|\/assets\/index-/.test(r.text)
for (const p of ['/actuator', '/actuator/info', '/actuator/env', '/actuator/health/../../api/public/config', '/actuator/health;/../../api/public/config',
  '/actuator/health/%2e%2e/%2e%2e/api/public/config', '//api/public/config', '/API/public/config', '/__gate/../api/public/config',
  '/__gate/%2e%2e/api/public/config', '/v3/api-docs', '/swagger-ui.html', '/index.html', '/assets/', '/error', '/ws']) {
  const r = await get(p, { accept: 'application/json' })
  check(`잠김 ${p}`, !(typeof r.status === 'number' && r.status >= 200 && r.status < 300) && !leaked(r), `${r.status}`)
}
for (const m of ['HEAD', 'OPTIONS']) {
  await sleep(150)
  const r = await raw(https, m, '/api/public/config', { origin: base.origin })
  check(`${m} /api/public/config 잠김`, !(r.status >= 200 && r.status < 300), `${r.status}`)
}
const wrongNext = await get('/__gate/?next=' + encodeURIComponent('/"><script>alert(1)</script>'), { accept: 'text/html' })
check('잠금화면 next 반사 XSS 없음', !/<script>alert\(1\)<\/script>/.test(wrongNext.text), `${wrongNext.status}`)

mkdirSync(OUT, { recursive: true })
const fails = rows.filter((r) => r.ok !== 'OK')
const table = '| ok | 점검 | 결과 |\n|---|---|---|\n' + rows.map((r) => `| ${r.ok} | ${r.name} | ${String(r.detail).replace(/\|/g, '\\|')} |`).join('\n')
writeFileSync(`${OUT}/prod-readonly.md`, `# 운영 익명 읽기 전용 점검 (${new Date().toISOString()})\n\n요청 약 ${rows.length + 5}건(GET/HEAD/OPTIONS·TLS 연결), 계정·접근 코드·쓰기 없음. 운영 주소는 <운영>으로 가림.\n\n${rows.length}건 중 기대와 다름 ${fails.length}건\n\n${table}\n`)
console.log(`checks=${rows.length} fail=${fails.length}`)
for (const f of fails) console.log('FAIL', f.name, f.detail)
