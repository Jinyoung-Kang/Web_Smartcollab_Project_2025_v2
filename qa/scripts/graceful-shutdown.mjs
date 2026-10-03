// 정상 종료(SIGTERM) 중 진행 중인 요청이 끝나는지 — 대조 실험
//  A) 본문을 다 보낸 뒤 서버가 오래 처리하는 요청(DeepL 목 10초 지연 번역)
//  B) 본문을 천천히 보내는 업로드(5MB 를 6초에 걸쳐) — 종료 신호는 1초 뒤
// 실행: node qa/scripts/graceful-shutdown.mjs
import { execSync } from 'node:child_process'
import net from 'node:net'
import { BASE, Client } from './lib.mjs'

const sh = (c) => execSync(c, { encoding: 'utf8' }).trim()
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
async function waitHealthy() {
  for (let i = 0; i < 240; i++) {
    try { if ((await fetch(`${BASE}/actuator/health`)).ok) return } catch { /* 기동 중 */ }
    await sleep(500)
  }
}
const U = await new Client('gs').signup()
const root = U.me.rootFolderId
const doc = (await U.upload(root, 'd.txt', '번역할 문장', 'text/plain')).data
await U.fetchCsrf()

// A
await fetch('http://localhost:19000/__mode', { method: 'POST', body: JSON.stringify({ delayMs: 10000 }) })
const t0 = Date.now()
const a = U.post(`/api/files/${doc.id}/translation?target=EN`).then((r) => ({ status: r.status, ms: Date.now() - t0 })).catch((e) => ({ error: e.cause?.code ?? e.message, ms: Date.now() - t0 }))
await sleep(1000)
const stopA = Date.now()
execSync('docker stop -t 40 sc-qa-app-1')
const resultA = await a
const stopAms = Date.now() - stopA
await fetch('http://localhost:19000/__mode', { method: 'POST', body: '{}' })
sh('docker start sc-qa-app-1'); await waitHealthy(); await U.login(U.username); await U.fetchCsrf()

// B
const resultB = await new Promise((resolve) => {
  const total = 5 * 1024 * 1024
  const head = '--b\r\nContent-Disposition: form-data; name="file"; filename="slow.bin"\r\nContent-Type: application/octet-stream\r\n\r\n'
  const tail = '\r\n--b--\r\n'
  const len = Buffer.byteLength(head) + total + Buffer.byteLength(tail)
  const s = net.connect(8080, '127.0.0.1', async () => {
    s.write(`POST /api/files/upload?folderId=${root} HTTP/1.1\r\nHost: localhost\r\nCookie: ${U.cookieHeader()}\r\nX-XSRF-TOKEN: ${U.cookies.get('XSRF-TOKEN')}\r\nContent-Type: multipart/form-data; boundary=b\r\nContent-Length: ${len}\r\nConnection: close\r\n\r\n${head}`)
    const chunk = Buffer.alloc(total / 60, 9)
    const started = Date.now()
    let stopped = false
    for (let i = 0; i < 60; i++) {
      if (s.destroyed) break
      s.write(chunk)
      await sleep(100)
      if (!stopped && Date.now() - started > 1000) { stopped = true; execSync('docker kill -s TERM sc-qa-app-1') }
    }
    if (!s.destroyed) s.write(tail)
  })
  let resp = ''
  s.on('data', (d) => (resp += d))
  s.on('close', () => resolve({ response: resp.split('\r\n')[0] || '(응답 없음)' }))
  s.on('error', (e) => resolve({ error: e.code }))
})
for (let i = 0; i < 100; i++) { if (sh("docker inspect -f '{{.State.Running}}' sc-qa-app-1") === 'false') break; await sleep(500) }
const log = sh("docker logs --since 2m sc-qa-app-1 2>&1 | grep -E 'Graceful|graceful' | cut -c1-200 | tail -4")
sh('docker start sc-qa-app-1'); await waitHealthy(); await U.login(U.username)
const listed = (await U.get(`/api/folders/${root}`)).data.items.map((i) => i.name)
console.log(JSON.stringify({ A_slowServerWork: { ...resultA, dockerStopMs: stopAms }, B_slowUploadBody: { ...resultB, listed: listed.includes('slow.bin') }, log }, null, 2))
