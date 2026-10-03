// 기능 경계값 — 문서·코드의 한도 바로 안쪽과 바깥쪽 값으로 기대 결과(허용/거절)를 확인합니다.
// 실행: node qa/scripts/boundary.mjs
import { writeFileSync } from 'node:fs'
import { Client, PASSWORD, table, uid } from './lib.mjs'

const U = await new Client('bd').signup()
const root = U.me.rootFolderId
const rows = []
const check = (name, actual, expected) => rows.push({ ok: expected.includes(actual) ? 'PASS' : 'FAIL', name, expected: expected.join('/'), actual })

// 이름 길이 255/256 (폴더·파일 이름 바꾸기·업로드)
check('폴더 이름 255자', (await U.post('/api/folders', { parentId: root, name: 'a'.repeat(255) })).status, [201])
check('폴더 이름 256자', (await U.post('/api/folders', { parentId: root, name: 'a'.repeat(256) })).status, [400])
const f = (await U.upload(root, 'b.txt', 'hello', 'text/plain')).data
check('파일 이름 255자로 바꾸기', (await U.patch(`/api/files/${f.id}`, { name: 'b'.repeat(251) + '.txt' })).status, [204])
check('파일 이름 256자로 바꾸기', (await U.patch(`/api/files/${f.id}`, { name: 'b'.repeat(252) + '.txt' })).status, [400])
check('업로드 이름 255자', (await U.upload(root, 'c'.repeat(251) + '.txt', 'x')).status, [201])
check('업로드 이름 256자', (await U.upload(root, 'c'.repeat(252) + '.txt', 'x')).status, [400])
check('한글 255자 이름(765바이트)', (await U.post('/api/folders', { parentId: root, name: '가'.repeat(255) })).status, [201])
check('이모지 255자 이름(4바이트 문자)', (await U.post('/api/folders', { parentId: root, name: '😀'.repeat(255) })).status, [201, 400])

// 빈 파일·텍스트 편집 한도(2MB)
check('0바이트 업로드(의도: 거절)', (await U.upload(root, 'empty.txt', '')).status, [400])
const t = (await U.upload(root, 'edit.txt', 'x', 'text/plain')).data
let v = (await U.get(`/api/files/${t.id}/content`)).data.versionId
const save = await U.put(`/api/files/${t.id}/content`, { content: 'a'.repeat(2 * 1024 * 1024), baseVersionId: v })
check('텍스트 저장 2,097,152바이트', save.status, [200])
v = save.data?.versionId ?? v
check('텍스트 저장 2,097,153바이트', (await U.put(`/api/files/${t.id}/content`, { content: 'a'.repeat(2 * 1024 * 1024 + 1), baseVersionId: v })).status, [413])
check('텍스트 저장 한글 699,051자(2,097,153바이트)', (await U.put(`/api/files/${t.id}/content`, { content: '가'.repeat(699051), baseVersionId: v })).status, [413])

// 폴더 깊이 50/51
let p = root
for (let i = 1; i <= 50; i++) p = (await U.createFolder(p, `d${i}`)).id
check('51단계 폴더 만들기', (await U.post('/api/folders', { parentId: p, name: 'd51' })).status, [400])

// 여러 항목 200/201
const ids = []
for (let i = 0; i < 201; i++) ids.push({ type: 'file', id: t.id })
check('items 200개(같은 항목 반복)', (await U.post('/api/items/copy', { items: ids.slice(0, 200), targetFolderId: root })).status, [200, 204])
check('items 201개', (await U.post('/api/items/copy', { items: ids, targetFolderId: root })).status, [400])

// 팀 이름 100/101, 채팅 2000/2001, 페이지 크기
check('팀 이름 100자', (await U.post('/api/teams', { name: 't'.repeat(100) })).status, [201])
check('팀 이름 101자', (await U.post('/api/teams', { name: 't'.repeat(101) })).status, [400])
const team = await U.createTeam('bd-team')
check('채팅 2000자', (await U.post(`/api/teams/${team.id}/messages`, { content: 'm'.repeat(2000) })).status, [200, 201])
check('채팅 2001자', (await U.post(`/api/teams/${team.id}/messages`, { content: 'm'.repeat(2001) })).status, [400])
check('채팅 이모지 2000자(서로게이트 4000)', (await U.post(`/api/teams/${team.id}/messages`, { content: '😀'.repeat(2000) })).status, [200, 201, 400])
check('채팅 공백만', (await U.post(`/api/teams/${team.id}/messages`, { content: '   ' })).status, [400])
for (const size of [0, 1, 100, 101, -1]) {
  const r = await U.get(`/api/teams/${team.id}/messages?size=${size}`)
  // 위에서 성공한 메시지는 2000자 하나뿐 — 크기가 범위를 벗어나도 1~100 으로 고쳐 200 을 돌려줌
  check(`채팅 기록 size=${size}`, r.status === 200 ? `200:${r.data.messages.length}` : r.status, ['200:1'])
}

// 검색어 길이·와일드카드
await U.upload(root, '100%_done.txt', 'x')
await U.upload(root, '100x done.txt', 'x')
check('검색어 100자', (await U.get(`/api/files/search?q=${'q'.repeat(100)}`)).status, [200])
check('검색어 101자', (await U.get(`/api/files/search?q=${'q'.repeat(101)}`)).status, [400])
const pct = (await U.get(`/api/files/search?q=${encodeURIComponent('%')}`)).data.map((x) => x.item.name)
check('검색 "%" 는 글자 그대로', pct.every((n) => n.includes('%')) && pct.length >= 1 ? 'ok' : JSON.stringify(pct), ['ok'])
const und = (await U.get(`/api/files/search?q=${encodeURIComponent('_do')}`)).data.map((x) => x.item.name)
check('검색 "_" 는 글자 그대로', JSON.stringify(und), [JSON.stringify(['100%_done.txt'])])

// 공유 링크 조건
const share = (b) => U.post(`/api/files/${t.id}/share-links`, b).then((r) => r.status)
check('유효 기간 720시간', await share({ expiresInHours: 720 }), [201])
check('유효 기간 721시간', await share({ expiresInHours: 721 }), [400])
check('유효 기간 0시간', await share({ expiresInHours: 0 }), [400])
check('다운로드 1000회', await share({ downloadLimit: 1000 }), [201])
check('다운로드 1001회', await share({ downloadLimit: 1001 }), [400])
check('비밀번호 3자', await share({ password: 'abc' }), [400])
check('비밀번호 4자', await share({ password: 'abcd' }), [201])
check('비밀번호 72자', await share({ password: 'p'.repeat(72) }), [201])
check('비밀번호 73자', await share({ password: 'p'.repeat(73) }), [400])
check('비밀번호 한글 25자(75바이트)', await share({ password: '가'.repeat(25) }), [400])

// 가입 경계
const su = (username, password = PASSWORD, extra = {}) =>
  new Client().post('/api/auth/signup', { username, password, passwordConfirm: password, name: 'n', ...extra }).then((r) => r.status)
check('아이디 3자', await su('ab' + 'c'), [400])
check('아이디 4자', await su(`b${uid().slice(0, 3)}`), [201])
check('아이디 20자', await su(`u${uid()}`.padEnd(20, 'x')), [201])
check('아이디 21자', await su(`u${uid()}`.padEnd(21, 'x')), [400])
check('비밀번호 7자', await su(`p${uid()}`, 'Abcdef1'), [400])
check('비밀번호 72바이트', await su(`p${uid()}`, 'A1' + 'a'.repeat(70)), [201])
check('비밀번호 73바이트', await su(`p${uid()}`, 'A1' + 'a'.repeat(71)), [400])
check('비밀번호 확인 불일치', await su(`p${uid()}`, PASSWORD, { passwordConfirm: 'Other1234' }), [400])
check('이름 50자', await su(`n${uid()}`, PASSWORD, { name: 'n'.repeat(50) }), [201])
check('이름 51자', await su(`n${uid()}`, PASSWORD, { name: 'n'.repeat(51) }), [400])
check('이메일 형식 오류', await su(`e${uid()}`, PASSWORD, { email: 'not-an-email' }), [400])

const fails = rows.filter((r) => r.ok === 'FAIL')
writeFileSync(new URL('../results/boundary.md', import.meta.url), `# 경계값 (${new Date().toISOString()})\n\n${rows.length}건 중 기대와 다름 ${fails.length}건\n\n${table(rows, ['ok', 'name', 'expected', 'actual'])}\n`)
console.log(`checks=${rows.length} fail=${fails.length}`)
for (const r of fails) console.log('FAIL', r.name, 'expected', r.expected, 'actual', r.actual)
