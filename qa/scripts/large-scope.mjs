// 큰 범위 측정 — 파일 1만 개 폴더 목록의 응답 크기·시간(gzip 포함/미포함), 파일이 많은 사용자와 적은 사용자의 업로드·사용량 조회 시간
// 실행: node qa/scripts/large-scope.mjs (perf-seed 뒤). 결과 폴더는 QA_OUT(기본 qa/results)
// 폴더 목록은 페이지가 생긴 뒤(IMP-02) 첫 묶음(기본 500개)과, 커서로 끝까지 받은 전체를 따로 잽니다.
import { readFileSync, writeFileSync } from 'node:fs'
import { get as httpGet } from 'node:http'
import { BASE, Client } from './lib.mjs'

const seed = JSON.parse(readFileSync(new URL('../results/perf-users.json', import.meta.url)))
const big = new Client(); await big.login(seed.users[0].username)
const small = new Client(); await small.login(seed.users[5].username)
const med = (xs) => xs.sort((a, b) => a - b)[Math.floor(xs.length / 2)]
const time = async (fn, n = 7) => { const xs = []; for (let i = 0; i < n; i++) { const s = performance.now(); await fn(); xs.push(performance.now() - s) } return Math.round(med(xs)) }

const out = {}
const listPath = `/api/folders/${seed.bigFolder.folderId}`
// 전송 크기(gzip) — fetch 는 압축을 풀어 돌려주므로, 풀지 않는 node:http 로 받은 바이트를 셉니다
const listGzip = await new Promise((resolve, reject) => httpGet(`${BASE}${listPath}`, { headers: { cookie: big.cookieHeader(), 'accept-encoding': 'gzip' } }, (res) => {
  let n = 0
  res.on('data', (c) => { n += c.length }).on('end', () => resolve({ bytes: n, encoding: res.headers['content-encoding'] ?? null })).on('error', reject)
}).on('error', reject))
const gz = listGzip.bytes
const plain = await big.get(listPath)
out.bigFolder = {
  items: plain.data.items.length, itemCount: plain.data.itemCount, paged: 'nextCursor' in plain.data,
  jsonBytes: plain.bytes, gzipBytes: gz, contentEncoding: listGzip.encoding, medianMs: await time(() => big.get(listPath)),
}
if (out.bigFolder.paged) {
  // 커서로 끝까지 — 화면이 아래로 끝까지 내렸을 때 받는 양
  const all = async () => {
    let cursor = null, pages = 0, items = 0, bytes = 0
    do {
      const r = await big.get(listPath + (cursor ? `?cursor=${encodeURIComponent(cursor)}` : ''))
      pages++; items += r.data.items.length; bytes += r.bytes; cursor = r.data.nextCursor
    } while (cursor)
    return { pages, items, jsonBytes: bytes }
  }
  out.bigFolderAllPages = { ...(await all()), medianMs: await time(all, 3) }
}
const files = (u) => u.get('/api/files/usage').then((r) => r.data.fileCount)
out.users = { bigUserFiles: await files(big), smallUserFiles: await files(small) }
out.usageMs = { bigUser: await time(() => big.get('/api/files/usage')), smallUser: await time(() => small.get('/api/files/usage')) }
out.uploadMs = {
  bigUser: await time(() => big.upload(seed.users[0].rootFolderId, `m-${Date.now()}.txt`, 'x', 'text/plain')),
  smallUser: await time(() => small.upload(seed.users[5].rootFolderId, `m-${Date.now()}.txt`, 'x', 'text/plain')),
}
out.searchMs = { bigUser: await time(() => big.get('/api/files/search?q=' + encodeURIComponent('항목-0999'))), smallUser: await time(() => small.get('/api/files/search?q=' + encodeURIComponent('문서-1'))) }
out.treeMs = { bigUser: await time(() => big.get('/api/folders/tree')) }
writeFileSync(`${process.env.QA_OUT ?? 'qa/results'}/large-scope.json`, JSON.stringify({ measuredAt: new Date().toISOString(), method: '호스트에서 QA 스택(포트 포워딩 포함), 각 7회 중앙값', ...out }, null, 2))
console.log(JSON.stringify(out, null, 2))
