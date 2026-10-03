// 장애 주입 — QA 스택(sc-qa)에서만. DB 끊김·지연(Toxiproxy), 앱 강제 종료, 저장소 쓰기 실패, 외부 API(DeepL 목) 지연·오류
// 실행: node qa/scripts/reliability.mjs [시나리오...]  (예: node qa/scripts/reliability.mjs db-cut kill-upload). 결과 폴더는 QA_OUT(기본 qa/results)
import { execSync } from 'node:child_process'
import { writeFileSync } from 'node:fs'
import { BASE, Client } from './lib.mjs'

const TOXI = 'http://localhost:18474'
const MOCK = 'http://localhost:19000'
const sh = (cmd) => execSync(cmd, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim()
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const toxi = (path, method = 'POST', body) =>
  fetch(TOXI + path, { method, headers: { 'content-type': 'application/json' }, body: body && JSON.stringify(body) }).then((r) => r.text())
const integrity = () => JSON.parse(sh('qa/scripts/integrity-check.sh sc-qa'))
async function waitHealthy(timeoutMs = 120_000) {
  const start = Date.now()
  while (Date.now() - start < timeoutMs) {
    try {
      const r = await fetch(`${BASE}/actuator/health`)
      if (r.ok) return Date.now() - start
    } catch {
      /* 아직 기동 중 */
    }
    await sleep(500)
  }
  throw new Error('앱이 다시 뜨지 않음')
}
async function timed(fn) {
  const s = performance.now()
  try {
    const r = await fn()
    return { status: r.status, ms: Math.round(performance.now() - s), code: r.data?.code }
  } catch (e) {
    return { status: 'ERR', ms: Math.round(performance.now() - s), error: String(e).slice(0, 80) }
  }
}

const results = {}
const only = process.argv.slice(2)
const run = async (name, fn) => {
  if (only.length && !only.includes(name)) return
  console.log(`== ${name}`)
  try {
    results[name] = await fn()
  } catch (e) {
    results[name] = { error: String(e) }
  }
  console.log(JSON.stringify(results[name], null, 2))
}

const U = await new Client('rel').signup()
const root = U.me.rootFolderId
const doc = (await U.upload(root, 'doc.txt', '원본 문서 내용입니다. 번역할 문장이 있습니다.', 'text/plain')).data

// R1. DB 연결 끊김 10초 — 그동안의 응답 시간·코드, 회복 시간, 정합성
await run('db-cut', async () => {
  await toxi('/proxies/mysql', 'POST', { enabled: false })
  const during = await Promise.all([
    timed(() => U.get(`/api/folders/${root}`)),
    timed(() => U.get('/api/teams')),
    timed(() => U.post('/api/folders', { parentId: root, name: 'during-outage' })),
    timed(() => fetch(`${BASE}/actuator/health/readiness`).then((r) => ({ status: r.status }))),
    timed(() => fetch(`${BASE}/actuator/health/liveness`).then((r) => ({ status: r.status }))),
  ])
  await sleep(10_000)
  await toxi('/proxies/mysql', 'POST', { enabled: true })
  const start = Date.now()
  let recovered = null
  while (Date.now() - start < 60_000) {
    const r = await U.get(`/api/folders/${root}`)
    if (r.status === 200) {
      recovered = Date.now() - start
      break
    }
    await sleep(250)
  }
  const after = await U.get(`/api/folders/${root}`)
  return { during, recoveredAfterMs: recovered, createdDuringOutage: after.data.items.some((i) => i.name === 'during-outage'), integrity: integrity() }
})

// R2. DB 지연 1초(왕복마다) — 동시 요청 30개의 응답 시간·오류
await run('db-latency', async () => {
  await toxi('/proxies/mysql/toxics', 'POST', { name: 'lat', type: 'latency', stream: 'downstream', attributes: { latency: 1000, jitter: 0 } })
  const reqs = await Promise.all(Array.from({ length: 30 }, (_, i) => timed(() => (i % 3 === 0 ? U.post('/api/folders', { parentId: root, name: `slow-${i}` }) : U.get(`/api/folders/${root}`)))))
  await toxi('/proxies/mysql/toxics/lat', 'DELETE')
  const ms = reqs.map((r) => r.ms).sort((a, b) => a - b)
  const statuses = reqs.reduce((m, r) => ((m[r.status] = (m[r.status] ?? 0) + 1), m), {})
  return { statuses, p50: ms[14], p95: ms[28], max: ms[29], integrity: integrity() }
})

// R3. 업로드 도중 앱 강제 종료(SIGKILL) → 재시작 → 유실·고아·임시 파일
await run('kill-upload', async () => {
  const big = Buffer.alloc(80 * 1024 * 1024, 7)
  const form = new FormData()
  form.append('file', new Blob([big]), 'big.bin')
  await U.fetchCsrf()
  const p = fetch(`${BASE}/api/files/upload?folderId=${root}`, { method: 'POST', headers: { cookie: U.cookieHeader(), 'X-XSRF-TOKEN': U.cookies.get('XSRF-TOKEN') }, body: form })
    .then((r) => r.status)
    .catch((e) => `ERR ${e.cause?.code ?? e.message}`)
  await sleep(Number(process.env.KILL_UPLOAD_MS ?? 600))
  sh('docker kill -s KILL sc-qa-app-1')
  const uploadResult = await p
  sh('docker start sc-qa-app-1')
  const restartMs = await waitHealthy()
  await U.login(U.username)
  const items = (await U.get(`/api/folders/${root}`)).data.items.map((i) => i.name)
  return { uploadResult, restartMs, bigListed: items.includes('big.bin'), integrity: integrity() }
})

// R4. 복사 도중 앱 강제 종료 — 파일 150개 폴더 복사
await run('kill-copy', async () => {
  const src = await U.createFolder(root, 'copy-src')
  for (let i = 0; i < 150; i++) await U.upload(src.id, `f${i}.bin`, Buffer.alloc(200 * 1024, i % 255))
  const dst = await U.createFolder(root, 'copy-dst')
  const before = integrity()
  const p = U.post('/api/items/copy', { items: [{ type: 'folder', id: src.id }], targetFolderId: dst.id }).then((r) => r.status).catch((e) => `ERR ${e.cause?.code ?? e.message}`)
  await sleep(Number(process.env.KILL_COPY_MS ?? 150))
  sh('docker kill -s KILL sc-qa-app-1')
  const copyResult = await p
  sh('docker start sc-qa-app-1')
  const restartMs = await waitHealthy()
  await U.login(U.username)
  const dstItems = (await U.get(`/api/folders/${dst.id}`)).data.items
  let copied = 0
  if (dstItems[0]) copied = (await U.get(`/api/folders/${dstItems[0].id}`)).data.items.length
  return { copyResult, restartMs, dstFolders: dstItems.length, copiedFiles: copied, before: { versions: before.versions, blobs: before.blobs }, integrity: integrity() }
})

// R5. 저장소 쓰기 실패(디렉터리 쓰기 금지) — 업로드·복사·텍스트 저장의 응답과 DB 잔여
await run('storage-readonly', async () => {
  const usageBefore = (await U.get('/api/files/usage')).data
  sh("docker exec -u root sc-qa-app-1 sh -c 'chmod -R a-w /data'")
  const up = await timed(() => U.upload(root, 'ro.txt', 'x', 'text/plain'))
  const content = await U.get(`/api/files/${doc.id}/content`)
  const save = await timed(() => U.put(`/api/files/${doc.id}/content`, { content: '저장 실패 시험', baseVersionId: content.data.versionId }))
  const copy = await timed(() => U.post('/api/items/copy', { items: [{ type: 'file', id: doc.id }], targetFolderId: root }))
  sh("docker exec -u root sc-qa-app-1 sh -c 'chmod -R u+w /data'")
  const usageAfter = (await U.get('/api/files/usage')).data
  const items = (await U.get(`/api/folders/${root}`)).data.items.map((i) => i.name)
  const after = await U.get(`/api/files/${doc.id}/content`)
  return { upload: up, save, copy, roListed: items.includes('ro.txt'), docUnchanged: after.data.versionId === content.data.versionId, usageBefore, usageAfter, integrity: integrity() }
})

// R6. 저장소 파일이 사라진 버전 — 다운로드·미리보기·텍스트 읽기의 응답
await run('missing-blob', async () => {
  const f = (await U.upload(root, 'gone.txt', 'will vanish', 'text/plain')).data
  const PW = sh("grep -E '^DB_ROOT_PASSWORD=' .env | cut -d= -f2-")
  const key = sh(`docker exec -e MYSQL_PWD='${PW}' sc-qa-db-1 mysql -uroot -N -B smartcollab -e "SELECT v.stored_path FROM files f JOIN file_versions v ON v.version_id=f.active_version_id WHERE f.file_id=${f.id}"`)
  sh(`docker exec -u root sc-qa-app-1 rm -f '/data/${key}'`)
  const res = {}
  for (const kind of ['download', 'view', 'content']) {
    const r = await fetch(`${BASE}/api/files/${f.id}/${kind}`, { headers: { cookie: U.cookieHeader() } })
    const body = await r.text()
    res[kind] = { status: r.status, bytes: body.length, code: (body.match(/"code":"(\w+)"/) ?? [])[1] }
  }
  res.copy = (await U.post('/api/items/copy', { items: [{ type: 'file', id: f.id }], targetFolderId: root })).status
  res.summary = (await U.post(`/api/files/${f.id}/summary`)).status
  res.log = sh("docker logs --since 20s sc-qa-app-1 2>&1 | grep -E 'ERROR|WARN' | cut -c1-160 | tail -5")
  return res
})

// R7. 외부 API(DeepL 목) 지연 35초·5xx·끊김 — 응답, 분량 반환, 다른 요청 영향
await run('deepl', async () => {
  const mode = (m) => fetch(`${MOCK}/__mode`, { method: 'POST', body: JSON.stringify(m) })
  await mode({})
  const ok = await timed(() => U.post(`/api/files/${doc.id}/translation?target=EN`))
  await mode({ status: 500 })
  const err500 = await timed(() => U.post(`/api/files/${doc.id}/translation?target=EN`))
  await mode({ drop: true })
  const dropped = await timed(() => U.post(`/api/files/${doc.id}/translation?target=EN`))
  await mode({ delayMs: 35_000 })
  // 느린 번역 15개를 동시에 걸어 두고, 그동안 다른 API 응답 시간
  const slow = Array.from({ length: 15 }, () => timed(() => U.post(`/api/files/${doc.id}/translation?target=EN`)))
  await sleep(2000)
  const others = await Promise.all(Array.from({ length: 10 }, () => timed(() => U.get(`/api/folders/${root}`))))
  const slowResults = await Promise.all(slow)
  await mode({})
  const afterOk = await timed(() => U.post(`/api/files/${doc.id}/translation?target=EN`))
  return { ok, err500, dropped, slow: slowResults.slice(0, 3), slowStatuses: slowResults.map((r) => r.status), othersMaxMs: Math.max(...others.map((o) => o.ms)), afterOk }
})

// R8. 정상 종료(SIGTERM) 중 진행 중인 업로드가 끝나는지
await run('graceful-upload', async () => {
  const big = Buffer.alloc(60 * 1024 * 1024, 3)
  const form = new FormData()
  form.append('file', new Blob([big]), 'graceful.bin')
  await U.fetchCsrf()
  const p = fetch(`${BASE}/api/files/upload?folderId=${root}`, { method: 'POST', headers: { cookie: U.cookieHeader(), 'X-XSRF-TOKEN': U.cookies.get('XSRF-TOKEN') }, body: form })
    .then((r) => r.status)
    .catch((e) => `ERR ${e.cause?.code ?? e.message}`)
  await sleep(300)
  // execSync('docker stop') 는 끝날 때까지 이벤트 루프를 막아 업로드 본문 전송까지 멈추므로, 신호만 보내고 비동기로 기다립니다
  sh('docker kill -s TERM sc-qa-app-1')
  const uploadResult = await p
  for (let i = 0; i < 100 && sh("docker inspect -f '{{.State.Running}}' sc-qa-app-1") === 'true'; i++) await sleep(500)
  const shutdownLog = sh("docker logs --since 2m sc-qa-app-1 2>&1 | grep -E 'Graceful shutdown (complete|aborted)' | tail -1 | sed -E 's/.*: //'")
  sh('docker start sc-qa-app-1')
  await waitHealthy()
  await U.login(U.username)
  const listed = (await U.get(`/api/folders/${root}`)).data.items.some((i) => i.name === 'graceful.bin')
  return { uploadResult, listed, shutdownLog, integrity: integrity() }
})

writeFileSync(`${process.env.QA_OUT ?? 'qa/results'}/reliability-${only.join('_') || 'all'}${process.env.KILL_UPLOAD_MS ? '-u' + process.env.KILL_UPLOAD_MS : ''}${process.env.KILL_COPY_MS ? '-c' + process.env.KILL_COPY_MS : ''}.json`, JSON.stringify(results, null, 2))
