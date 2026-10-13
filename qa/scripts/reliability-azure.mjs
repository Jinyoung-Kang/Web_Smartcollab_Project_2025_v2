// [6차 QA D3] Azure 저장소(Azurite) 장애 — Toxiproxy "blob" 으로 지연·끊김·무응답을 주입하고, 사라진 blob 의 응답을 봅니다.
// 사용: QA 스택을 qa/compose.azure.yml 로 띄운 뒤 DB_ROOT_PASSWORD=… node qa/scripts/reliability-azure.mjs [시나리오…]
import { execSync } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { BASE, Client } from './lib.mjs'

const OUT = process.env.QA_OUT ?? 'qa/results/qa-2026-10-13/reliability'
const TOXI = 'http://localhost:18474'
const sh = (cmd) => execSync(cmd, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim()
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const toxi = (path, method = 'POST', body) =>
  fetch(TOXI + path, { method, headers: { 'content-type': 'application/json' }, body: body ? JSON.stringify(body) : undefined })
const integrity = () => JSON.parse(sh('qa/scripts/integrity-check.sh sc-qa'))
const AZ_CS = `DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=${sh("grep -oE 'AccountKey=[^;]+' qa/compose.azure.yml | cut -d= -f2-")};BlobEndpoint=http://127.0.0.1:10010/devstoreaccount1;`
const sql = (q) => sh(`docker exec -e MYSQL_PWD="$DB_ROOT_PASSWORD" sc-qa-db-1 mysql -uroot -N -B smartcollab -e "${q}"`)

async function timed(fn) {
  const t = performance.now()
  try {
    const r = await fn()
    return { status: r.status, ms: Math.round(performance.now() - t), code: r.data?.code ?? (String(r.text ?? '').match(/"code":"(\w+)"/) ?? [])[1] }
  } catch (e) {
    return { status: `ERR ${e.cause?.code ?? e.message}`, ms: Math.round(performance.now() - t) }
  }
}
const results = {}
const only = process.argv.slice(2)
const run = async (name, fn) => {
  if (only.length && !only.includes(name)) return
  try {
    results[name] = await fn()
  } catch (e) {
    results[name] = { error: String(e.stack ?? e) }
  } finally {
    await toxi('/proxies/blob', 'POST', { enabled: true })
    for (const t of ['lat', 'stop', 'slice']) await toxi(`/proxies/blob/toxics/${t}`, 'DELETE').catch(() => {})
  }
  console.log(name, JSON.stringify(results[name]).slice(0, 400))
}

const U = await new Client('az').signup()
const root = U.me.rootFolderId
const big = Buffer.alloc(9 * 1024 * 1024, 7)   // 4MB 블록 3개
const seed = (await U.upload(root, 'seed.txt', '저장소 장애 시험용 원본', 'text/plain')).data

// 저장소가 느릴 때(블록마다 2초) — 업로드·내려받기가 끝나고 다른 API 는 영향 없음
await run('blob-latency', async () => {
  await toxi('/proxies/blob/toxics', 'POST', { name: 'lat', type: 'latency', stream: 'downstream', attributes: { latency: 2000, jitter: 0 } })
  const [up, down, list] = await Promise.all([
    timed(() => U.upload(root, 'slow.bin', big)),
    timed(() => U.get(`/api/files/${seed.id}/download`)),
    (async () => { await sleep(500); return timed(() => U.get(`/api/folders/${root}`)) })(),
  ])
  return { up, down, list, integrity: integrity() }
})

// 저장소가 끊겼을 때 — 업로드는 503(DB 에 행 없음), 내려받기 503, 저장소를 쓰지 않는 API 는 정상, 영구 삭제는 DB 만 지우고 blob 은 고아로
await run('blob-down', async () => {
  const keep = (await U.upload(root, 'keep.txt', 'k', 'text/plain')).data
  await toxi('/proxies/blob', 'POST', { enabled: false })
  const up = await timed(() => U.upload(root, 'down.bin', big))
  const down = await timed(() => U.get(`/api/files/${seed.id}/download`))
  const list = await timed(() => U.get(`/api/folders/${root}`))
  const rename = await timed(() => U.patch(`/api/files/${keep.id}`, { name: 'keep2.txt' }))
  const trash = await timed(() => U.del(`/api/files/${keep.id}`))
  const purge = await timed(() => U.del(`/api/trash/${keep.id}`))
  const summary = await timed(() => U.post(`/api/files/${seed.id}/summary`))
  await toxi('/proxies/blob', 'POST', { enabled: true })
  await sleep(1000)
  const listedDown = (await U.get(`/api/folders/${root}`)).data.items.some((i) => i.name === 'down.bin')
  return { up, down, list, rename, trash, purge, summary, listedDown, integrity: integrity() }
})

// 저장소가 연결은 받고 응답하지 않을 때 — 요청이 상한(약 95초) 안에 503 으로 끝나는지
await run('blob-hang', async () => {
  await toxi('/proxies/blob/toxics', 'POST', { name: 'stop', type: 'timeout', stream: 'downstream', attributes: { timeout: 0 } })
  const [up, down, health] = await Promise.all([
    timed(() => U.upload(root, 'hang.bin', Buffer.alloc(1024 * 1024, 1))),
    timed(() => U.get(`/api/files/${seed.id}/download`)),
    (async () => { await sleep(3000); return timed(() => fetch(`${BASE}/actuator/health/readiness`).then((r) => ({ status: r.status }))) })(),
  ])
  return { up, down, health, integrity: integrity() }
})

// 업로드 도중 연결이 끊김(블록 하나를 보내는 중) — 반쪽 업로드가 남지 않는지
await run('blob-cut-mid-upload', async () => {
  const p = timed(() => U.upload(root, 'cut.bin', big))
  await sleep(400)
  await toxi('/proxies/blob', 'POST', { enabled: false })
  await sleep(1500)
  await toxi('/proxies/blob', 'POST', { enabled: true })
  const up = await p
  const listed = (await U.get(`/api/folders/${root}`)).data.items.some((i) => i.name === 'cut.bin')
  return { up, listed, integrity: integrity() }
})

// DB 에는 있는데 저장소에서 blob 이 사라진 파일 — 500 이 아니라 안내가 있는 오류
await run('blob-missing', async () => {
  const f = (await U.upload(root, 'gone.txt', 'will vanish', 'text/plain')).data
  const key = sql(`SELECT v.stored_path FROM files f JOIN file_versions v ON v.version_id=f.active_version_id WHERE f.file_id=${f.id}`)
  sh(`az storage blob delete --connection-string "${AZ_CS}" -c smartcollab-qa -n '${key}' -o none`)
  const res = {}
  for (const kind of ['download', 'view', 'content']) res[kind] = await timed(() => U.get(`/api/files/${f.id}/${kind}`))
  res.copy = await timed(() => U.post('/api/items/copy', { items: [{ type: 'file', id: f.id }], targetFolderId: root }))
  res.summary = await timed(() => U.post(`/api/files/${f.id}/summary`))
  res.officeUrl = await timed(() => U.get(`/api/files/${f.id}/office-preview-url`))
  res.log = sh("docker logs --since 20s sc-qa-app-1 2>&1 | grep -E ' ERROR ' | cut -c1-200 | tail -5")
  return res
})

mkdirSync(OUT, { recursive: true })
writeFileSync(`${OUT}/reliability-azure-${only.join('_') || 'all'}.json`, JSON.stringify(results, null, 2))
