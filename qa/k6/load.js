// k6 부하 측정 — QA 스택에서만. 시드: node qa/scripts/perf-seed.mjs
// 읽기:  k6 run -e MIX=read  -e VUS=50 -e DURATION=60s qa/k6/load.js
// 쓰기:  k6 run -e MIX=write -e VUS=10 -e DURATION=60s qa/k6/load.js
// 로그인: k6 run -e MIX=login -e VUS=20 -e DURATION=30s qa/k6/load.js
import http from 'k6/http'
import { check } from 'k6'
import { SharedArray } from 'k6/data'
import exec from 'k6/execution'

const BASE = __ENV.BASE || 'http://localhost:8080'
const MIX = __ENV.MIX || 'read'
const seed = JSON.parse(open('../results/perf-users.json'))
const users = new SharedArray('users', () => seed.users)

export const options = {
  vus: Number(__ENV.VUS || 10),
  duration: __ENV.DURATION || '60s',
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  discardResponseBodies: false,
  // 측정 기준(D2 제안): 읽기 p95 ≤ 300ms, 쓰기 p95 ≤ 800ms, 오류율 < 1%. 이름별 지표를 요약에 남기려고 이름마다 기준을 둡니다.
  thresholds: Object.fromEntries([
    ['http_req_failed', ['rate<0.01']],
    ...['folder', 'tree', 'search', 'notifications', 'chat', 'text', 'team', 'download10mb'].map((n) => [`http_req_duration{name:${n}}`, ['p(95)<300']]),
    ...['upload1mb', 'textSave', 'createFolder', 'chatSend', 'login'].map((n) => [`http_req_duration{name:${n}}`, ['p(95)<800']]),
  ]),
}

let loggedIn = false
let me

function csrfHeader() {
  const jar = http.cookieJar()
  const token = jar.cookiesForURL(BASE)['XSRF-TOKEN']
  return token ? { 'X-XSRF-TOKEN': token[0] } : {}
}

function login(u) {
  http.get(`${BASE}/api/auth/csrf`, { tags: { name: 'csrf' } })
  const r = http.post(`${BASE}/api/auth/login`, JSON.stringify({ username: u.username, password: seed.password }), {
    headers: { 'content-type': 'application/json', ...csrfHeader() },
    tags: { name: 'login' },
  })
  check(r, { 'login 200': (x) => x.status === 200 })
  http.get(`${BASE}/api/auth/csrf`, { tags: { name: 'csrf' } })
  return r.status === 200
}

const PAYLOAD_1MB = 'x'.repeat(1024 * 1024)

export default function () {
  const u = users[(exec.vu.idInTest - 1) % users.length]
  if (MIX === 'login') {
    http.cookieJar().clear(BASE)
    login(u)
    return
  }
  if (!loggedIn) {
    loggedIn = login(u)
    me = u
  }
  const json = { headers: { 'content-type': 'application/json', ...csrfHeader() } }
  const pick = Math.random()
  if (MIX === 'read') {
    if (pick < 0.3) check(http.get(`${BASE}/api/folders/${me.folderId}`, { tags: { name: 'folder' } }), { ok: (r) => r.status === 200 })
    else if (pick < 0.4) check(http.get(`${BASE}/api/folders/tree`, { tags: { name: 'tree' } }), { ok: (r) => r.status === 200 })
    else if (pick < 0.5) check(http.get(`${BASE}/api/files/search?q=${encodeURIComponent('문서-1')}`, { tags: { name: 'search' } }), { ok: (r) => r.status === 200 })
    else if (pick < 0.6) check(http.get(`${BASE}/api/notifications`, { tags: { name: 'notifications' } }), { ok: (r) => r.status === 200 })
    else if (pick < 0.7) check(http.get(`${BASE}/api/teams/${me.teamId}/messages?size=30`, { tags: { name: 'chat' } }), { ok: (r) => r.status === 200 })
    else if (pick < 0.8) check(http.get(`${BASE}/api/files/${me.textFileId}/content`, { tags: { name: 'text' } }), { ok: (r) => r.status === 200 })
    else if (pick < 0.9) check(http.get(`${BASE}/api/teams/${me.teamId}`, { tags: { name: 'team' } }), { ok: (r) => r.status === 200 })
    else check(http.get(`${BASE}/api/files/${me.bigFileId}/download`, { tags: { name: 'download10mb' }, responseType: 'none' }), { ok: (r) => r.status === 200 })
  } else if (MIX === 'write') {
    if (pick < 0.35) {
      const form = { file: http.file(PAYLOAD_1MB, `up-${exec.vu.idInTest}-${exec.scenario.iterationInTest}.bin`, 'application/octet-stream') }
      const r = http.post(`${BASE}/api/files/upload?folderId=${me.rootFolderId}`, form, { headers: csrfHeader(), tags: { name: 'upload1mb' } })
      check(r, { ok: (x) => x.status === 201 })
    } else if (pick < 0.65) {
      const cur = http.get(`${BASE}/api/files/${me.textFileId}/content`, { tags: { name: 'text' } })
      const base = cur.json('versionId')
      const r = http.put(`${BASE}/api/files/${me.textFileId}/content`, JSON.stringify({ content: `저장 ${Date.now()} `.repeat(20), baseVersionId: base }), { ...json, tags: { name: 'textSave' } })
      check(r, { ok: (x) => x.status === 200 || x.status === 409 })
    } else if (pick < 0.85) {
      const r = http.post(`${BASE}/api/folders`, JSON.stringify({ parentId: me.folderId, name: `f-${Date.now()}` }), { ...json, tags: { name: 'createFolder' } })
      check(r, { ok: (x) => x.status === 201 })
    } else {
      const r = http.post(`${BASE}/api/teams/${me.teamId}/messages`, JSON.stringify({ content: `부하 메시지 ${Date.now()}` }), { ...json, tags: { name: 'chatSend' } })
      check(r, { ok: (x) => x.status === 200 || x.status === 201 })
    }
  }
}

export function handleSummary(data) {
  const out = { mix: MIX, vus: options.vus, duration: options.duration, at: new Date().toISOString(), totals: {}, byName: {} }
  const m = data.metrics
  out.totals = {
    requests: m.http_reqs?.values.count,
    rps: m.http_reqs?.values.rate,
    failedRate: m.http_req_failed?.values.rate,
    p95: m.http_req_duration?.values['p(95)'],
  }
  for (const [k, v] of Object.entries(m)) {
    const g = /^http_req_duration\{name:(\w+)\}$/.exec(k)
    if (g) out.byName[g[1]] = v.values
  }
  return {
    [`qa/results/k6-${MIX}-${options.vus}.json`]: JSON.stringify(out, null, 2),
    stdout: `${MIX} vus=${options.vus} reqs=${out.totals.requests} rps=${out.totals.rps?.toFixed(1)} failed=${(out.totals.failedRate * 100).toFixed(2)}% p95=${out.totals.p95?.toFixed(1)}ms\n`,
  }
}
