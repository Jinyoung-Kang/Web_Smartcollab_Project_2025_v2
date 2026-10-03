// QA 용 DeepL 목 서버. 실제 DeepL 을 부르지 않고 지연·오류·끊김을 만듭니다.
// 동작 바꾸기: POST /__mode {"delayMs":35000,"status":500,"drop":false} · 호출 수 확인: GET /__calls
import http from 'node:http'

let mode = { delayMs: 0, status: 200, drop: false }
let calls = 0

const server = http.createServer((req, res) => {
  let body = ''
  req.on('data', (c) => (body += c))
  req.on('end', () => {
    if (req.url === '/__mode' && req.method === 'POST') {
      mode = { delayMs: 0, status: 200, drop: false, ...JSON.parse(body || '{}') }
      res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify(mode))
      return
    }
    if (req.url === '/__calls') {
      res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify({ calls, mode }))
      return
    }
    if (req.url === '/v2/translate' && req.method === 'POST') {
      calls++
      const { text = [], target_lang: target = 'EN' } = JSON.parse(body || '{}')
      setTimeout(() => {
        if (mode.drop) return req.socket.destroy()
        if (mode.status !== 200) {
          res.writeHead(mode.status, { 'content-type': 'application/json' }).end('{"message":"mock error"}')
          return
        }
        const translations = text.map((t) => ({ detected_source_language: 'KO', text: `[${target}] ${t}` }))
        res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify({ translations }))
      }, mode.delayMs)
      return
    }
    res.writeHead(404).end()
  })
})
server.listen(9000, () => console.log('deepl-mock :9000'))
