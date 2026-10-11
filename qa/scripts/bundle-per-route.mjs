// [L-9] 화면별로 처음 내려받는 JS 크기: index.html 의 진입 스크립트와 그 화면 청크에서 정적 import 를 따라가 합칩니다.
// 사용: (frontend 에서 npm run build 뒤) node qa/scripts/bundle-per-route.mjs
import { readFileSync, readdirSync } from 'node:fs'
import { gzipSync } from 'node:zlib'
import { join } from 'node:path'

const dist = new URL('../../frontend/dist/', import.meta.url).pathname
const assets = join(dist, 'assets')
const html = readFileSync(join(dist, 'index.html'), 'utf8')
const entry = [...html.matchAll(/src="\/assets\/([^"]+\.js)"/g)].map((m) => m[1])

function closure(starts) {
  const seen = new Set()
  const walk = (file) => {
    if (seen.has(file)) return
    seen.add(file)
    const code = readFileSync(join(assets, file), 'utf8')
    // 정적 import 만 (동적 import("./x.js") 는 그 화면에 갈 때 받음)
    for (const m of code.matchAll(/(?:^|[;\n}])\s*import\s*(?:[\w${},*\s]+from\s*)?["']\.\/([^"']+\.js)["']/g)) walk(m[1])
  }
  starts.forEach(walk)
  return [...seen]
}

const size = (files) => files.reduce((acc, f) => {
  const buf = readFileSync(join(assets, f))
  return { raw: acc.raw + buf.length, gzip: acc.gzip + gzipSync(buf).length }
}, { raw: 0, gzip: 0 })

const chunk = (name) => readdirSync(assets).find((f) => f.startsWith(`${name}-`) && f.endsWith('.js'))
const kb = (n) => `${(n / 1024).toFixed(1)} KB`
const routes = { '/share/:token (비로그인)': ['SharePage'], '/login': ['LoginPage'], '/drive (로그인 뒤)': ['AppShell', 'DrivePage'] }
for (const [route, names] of Object.entries(routes)) {
  const files = closure([...entry, ...names.map(chunk).filter(Boolean)])
  const s = size(files)
  console.log(`${route}\tJS ${files.length}개\t${kb(s.raw)}\tgzip ${kb(s.gzip)}\t${files.join(' ')}`)
}
