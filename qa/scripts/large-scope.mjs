// 큰 범위 측정 — 파일 1만 개 폴더 목록의 응답 크기·시간(gzip 포함/미포함), 파일이 많은 사용자와 적은 사용자의 업로드·사용량 조회 시간
// 실행: node qa/scripts/large-scope.mjs (perf-seed 뒤)
import { readFileSync, writeFileSync } from 'node:fs'
import { BASE, Client } from './lib.mjs'

const seed = JSON.parse(readFileSync(new URL('../results/perf-users.json', import.meta.url)))
const big = new Client(); await big.login(seed.users[0].username)
const small = new Client(); await small.login(seed.users[5].username)
const med = (xs) => xs.sort((a, b) => a - b)[Math.floor(xs.length / 2)]
const time = async (fn, n = 7) => { const xs = []; for (let i = 0; i < n; i++) { const s = performance.now(); await fn(); xs.push(performance.now() - s) } return Math.round(med(xs)) }

const out = {}
const listGzip = await fetch(`${BASE}/api/folders/${seed.bigFolder.folderId}`, { headers: { cookie: big.cookieHeader(), 'accept-encoding': 'gzip' } })
const gz = Buffer.from(await listGzip.arrayBuffer()).length
const plain = await big.get(`/api/folders/${seed.bigFolder.folderId}`)
out.bigFolder = { items: plain.data.items.length, jsonBytes: plain.bytes, gzipBytes: gz, contentEncoding: listGzip.headers.get('content-encoding'), medianMs: await time(() => big.get(`/api/folders/${seed.bigFolder.folderId}`)) }
const files = (u) => u.get('/api/files/usage').then((r) => r.data.fileCount)
out.users = { bigUserFiles: await files(big), smallUserFiles: await files(small) }
out.usageMs = { bigUser: await time(() => big.get('/api/files/usage')), smallUser: await time(() => small.get('/api/files/usage')) }
out.uploadMs = {
  bigUser: await time(() => big.upload(seed.users[0].rootFolderId, `m-${Date.now()}.txt`, 'x', 'text/plain')),
  smallUser: await time(() => small.upload(seed.users[5].rootFolderId, `m-${Date.now()}.txt`, 'x', 'text/plain')),
}
out.searchMs = { bigUser: await time(() => big.get('/api/files/search?q=' + encodeURIComponent('항목-0999'))), smallUser: await time(() => small.get('/api/files/search?q=' + encodeURIComponent('문서-1'))) }
out.treeMs = { bigUser: await time(() => big.get('/api/folders/tree')) }
writeFileSync(new URL('../results/large-scope.json', import.meta.url), JSON.stringify({ measuredAt: new Date().toISOString(), method: '호스트에서 QA 스택(포트 포워딩 포함), 각 7회 중앙값', ...out }, null, 2))
console.log(JSON.stringify(out, null, 2))
