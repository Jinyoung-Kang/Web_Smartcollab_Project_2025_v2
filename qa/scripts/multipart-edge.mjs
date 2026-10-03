// 깨진 multipart·중단된 업로드가 어떤 응답·로그를 남기는지
// 실행: node qa/scripts/multipart-edge.mjs
import net from 'node:net'
import { Client } from './lib.mjs'

const U = await new Client('mp').signup()
await U.fetchCsrf()
const root = U.me.rootFolderId
const headers = { cookie: U.cookieHeader(), 'X-XSRF-TOKEN': U.cookies.get('XSRF-TOKEN') }

async function raw(name, body, ct) {
  const r = await fetch(`http://localhost:8080/api/files/upload?folderId=${root}`, { method: 'POST', headers: { ...headers, 'content-type': ct }, body })
  console.log(name, r.status, (await r.text()).slice(0, 140))
}
await raw('경계 문자열 없음', 'abc', 'multipart/form-data')
await raw('경계가 본문과 다름', '--zzz\r\nContent-Disposition: form-data; name="file"; filename="a.txt"\r\n\r\nhi\r\n--zzz--\r\n', 'multipart/form-data; boundary=yyy')
await raw('끝 경계 없음', '--b\r\nContent-Disposition: form-data; name="file"; filename="a.txt"\r\n\r\nhi', 'multipart/form-data; boundary=b')
await raw('file 파트 없음', '--b\r\nContent-Disposition: form-data; name="other"\r\n\r\nhi\r\n--b--\r\n', 'multipart/form-data; boundary=b')
await raw('파일명 없음', '--b\r\nContent-Disposition: form-data; name="file"\r\n\r\nhi\r\n--b--\r\n', 'multipart/form-data; boundary=b')
await raw('빈 파일명', '--b\r\nContent-Disposition: form-data; name="file"; filename=""\r\n\r\nhi\r\n--b--\r\n', 'multipart/form-data; boundary=b')
await raw('파일 이름에 NUL', '--b\r\nContent-Disposition: form-data; name="file"; filename="a\u0000.txt"\r\n\r\nhi\r\n--b--\r\n', 'multipart/form-data; boundary=b')
await raw('file 파트 두 개', '--b\r\nContent-Disposition: form-data; name="file"; filename="a.txt"\r\n\r\nA\r\n--b\r\nContent-Disposition: form-data; name="file"; filename="b.txt"\r\n\r\nB\r\n--b--\r\n', 'multipart/form-data; boundary=b')

// 중단된 업로드: Content-Length 를 크게 알리고 일부만 보낸 뒤 연결을 끊음
await new Promise((resolve) => {
  const s = net.connect(8080, '127.0.0.1', () => {
    const part = '--b\r\nContent-Disposition: form-data; name="file"; filename="big.bin"\r\nContent-Type: application/octet-stream\r\n\r\n'
    s.write(`POST /api/files/upload?folderId=${root} HTTP/1.1\r\nHost: localhost\r\nCookie: ${headers.cookie}\r\nX-XSRF-TOKEN: ${headers['X-XSRF-TOKEN']}\r\nContent-Type: multipart/form-data; boundary=b\r\nContent-Length: 50000000\r\n\r\n${part}`)
    s.write(Buffer.alloc(5_000_000, 1))
    setTimeout(() => { s.destroy(); console.log('중단: 5MB 보낸 뒤 연결 끊음'); resolve() }, 1500)
  })
  s.on('error', () => {})
})
await new Promise((r) => setTimeout(r, 1500))
const list = await U.get(`/api/folders/${root}`)
console.log('폴더 항목', list.data.items.map((i) => i.name))
const usage = await U.get('/api/files/usage')
console.log('사용량', usage.data)
