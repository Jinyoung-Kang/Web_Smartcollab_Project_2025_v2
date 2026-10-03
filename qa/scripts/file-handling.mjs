// 파일 처리 보안 — 업로드·이름 바꾸기의 파일명 조작, 다운로드 헤더, 미리보기 응답(형식·CSP), 저장 경로
// 실행: node qa/scripts/file-handling.mjs
import { writeFileSync } from 'node:fs'
import { BASE, Client, table } from './lib.mjs'

const U = await new Client('files').signup()
const root = U.me.rootFolderId
const rows = []

const NAMES = [
  'a"b.txt',
  "it's.txt",
  'semi;colon.txt',
  'x\r\ny.txt',
  'x\ny.txt',
  '../../escape.txt',
  '..\\..\\escape.txt',
  'dir/inner.txt',
  '‮txt.exe',
  'CON.txt',
  '.htaccess',
  ' ',
  '.',
  '..',
  'a'.repeat(255) + '.txt',
  '한글 파일 이름.txt',
  '😀.txt',
  'file%20name%0d%0a.txt',
  'name\u0000.txt',
  '<img src=x onerror=alert(1)>.txt',
]

for (const name of NAMES) {
  const up = await U.upload(root, name, 'payload', 'text/plain')
  const row = { name: JSON.stringify(name).slice(0, 40), upload: up.status, stored: '', disposition: '', note: '' }
  if (up.status === 201 || up.status === 200) {
    row.stored = JSON.stringify(up.data.name).slice(0, 40)
    const res = await fetch(`${BASE}/api/files/${up.data.id}/download`, { headers: { cookie: U.cookieHeader() } })
    const cd = res.headers.get('content-disposition') ?? ''
    row.disposition = cd.slice(0, 80)
    const raw = [...res.headers.entries()].map(([k, v]) => `${k}: ${v}`).join('\n')
    if (/set-cookie: x=1/i.test(raw) || /\r|\n/.test(cd)) row.note = '헤더 주입'
    if (/[\\/]/.test(up.data.name)) row.note += ' 경로 구분자 저장'
  } else {
    row.stored = up.data?.detail ?? up.text.slice(0, 60)
  }
  rows.push(row)
}

// 이름 바꾸기에도 같은 값
const target = (await U.upload(root, 'rename-me.txt', 'x', 'text/plain')).data
const renameRows = []
for (const name of NAMES) {
  const r = await U.patch(`/api/files/${target.id}`, { name })
  renameRows.push({ name: JSON.stringify(name).slice(0, 40), status: r.status, result: r.status < 300 ? JSON.stringify(r.data?.name ?? '').slice(0, 40) : (r.data?.detail ?? '').slice(0, 60) })
  const f = await U.patch(`/api/folders/${(await U.createFolder(root, `rf${renameRows.length}`)).id}`, { name })
  renameRows[renameRows.length - 1].folder = f.status
}

// 미리보기: 형식별 Content-Type·CSP·nosniff
const previews = [
  ['evil.svg', '<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>', 'image/svg+xml'],
  ['evil.html', '<script>alert(1)</script>', 'text/html'],
  ['evil.htm', '<script>alert(1)</script>', 'text/html'],
  ['evil.xhtml', '<html xmlns="http://www.w3.org/1999/xhtml"><script>alert(1)</script></html>', 'application/xhtml+xml'],
  ['evil.txt', '<script>alert(1)</script>', 'text/html'],
  ['evil.png', '<script>alert(1)</script>', 'image/png'],
  ['evil.pdf', '%PDF-1.4\n<script>alert(1)</script>', 'application/pdf'],
  ['evil.js', 'alert(1)', 'text/javascript'],
  ['evil.md', '<script>alert(1)</script>', 'text/markdown'],
  ['evil', '<script>alert(1)</script>', 'text/html'],
]
const previewRows = []
for (const [name, body, type] of previews) {
  const up = await U.upload(root, name, body, type)
  const id = up.data.id
  for (const kind of ['view', 'download']) {
    const res = await fetch(`${BASE}/api/files/${id}/${kind}`, { headers: { cookie: U.cookieHeader() } })
    previewRows.push({
      file: name,
      kind,
      status: res.status,
      type: res.headers.get('content-type'),
      disposition: (res.headers.get('content-disposition') ?? '').split(';')[0],
      nosniff: res.headers.get('x-content-type-options'),
      csp: (res.headers.get('content-security-policy') ?? '').includes('sandbox') ? 'sandbox' : (res.headers.get('content-security-policy') ? 'csp(샌드박스 없음)' : '없음'),
    })
    await res.arrayBuffer()
  }
}

// 공유 다운로드(비로그인)의 헤더
const shared = (await U.upload(root, 'x"\r\nSet-Cookie: a=1.txt', 'x', 'text/plain'))
let shareRow = { upload: shared.status }
if (shared.status < 300) {
  const link = (await U.post(`/api/files/${shared.data.id}/share-links`, {})).data
  const res = await fetch(`${BASE}/api/public/shares/${link.token}/download`)
  shareRow = { upload: shared.status, status: res.status, disposition: res.headers.get('content-disposition'), setCookie: res.headers.getSetCookie().map((c) => c.replace(/=([^;]+)/, '=…')).join(' ; ') }
}

const out = `# 파일 처리 점검 (${new Date().toISOString()})

## 업로드 파일명
${table(rows, ['name', 'upload', 'stored', 'disposition', 'note'])}

## 이름 바꾸기 (파일·폴더)
${table(renameRows, ['name', 'status', 'result', 'folder'])}

## 미리보기·다운로드 응답 헤더
${table(previewRows, ['file', 'kind', 'status', 'type', 'disposition', 'nosniff', 'csp'])}

## 공유 다운로드 헤더
\`\`\`
${JSON.stringify(shareRow, null, 2)}
\`\`\`
`
writeFileSync(new URL('../results/file-handling.md', import.meta.url), out)
console.log(out)
