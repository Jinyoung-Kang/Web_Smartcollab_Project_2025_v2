// QA 점검 스크립트 공용 클라이언트. 브라우저처럼 쿠키(SC_AUTH·XSRF-TOKEN)와 CSRF 헤더로 요청합니다.
// 기본 대상은 QA 스택(http://localhost:8080). 운영 주소에는 쓰지 마세요.
export const BASE = process.env.QA_BASE_URL ?? 'http://localhost:8080'
export const PASSWORD = 'QaPassw0rd!'

if (!/^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(BASE)) {
  throw new Error(`QA 스크립트는 로컬 주소에만 씁니다: ${BASE}`)
}

export const uid = () => Math.random().toString(36).slice(2, 8)

export class Client {
  constructor(label = 'anon') {
    this.label = label
    this.cookies = new Map()
  }

  cookieHeader() {
    return [...this.cookies].map(([k, v]) => `${k}=${v}`).join('; ')
  }

  store(res) {
    for (const c of res.headers.getSetCookie?.() ?? []) {
      const [kv] = c.split(';')
      const i = kv.indexOf('=')
      const k = kv.slice(0, i).trim()
      const v = kv.slice(i + 1).trim()
      if (v === '' || /max-age=0/i.test(c)) this.cookies.delete(k)
      else this.cookies.set(k, v)
    }
  }

  /** 상태 변경 요청에는 CSRF 헤더를 붙입니다(csrf:false 면 생략). */
  async req(method, path, { json, body, headers = {}, csrf = true, cookies = true } = {}) {
    const h = { ...headers }
    if (cookies && this.cookies.size) h.cookie = this.cookieHeader()
    if (json !== undefined) h['content-type'] = 'application/json'
    if (csrf && !['GET', 'HEAD'].includes(method)) {
      if (!this.cookies.has('XSRF-TOKEN')) await this.fetchCsrf()
      h['X-XSRF-TOKEN'] = this.cookies.get('XSRF-TOKEN')
      if (cookies) h.cookie = this.cookieHeader()
    }
    const started = performance.now()
    const res = await fetch(BASE + path, {
      method,
      headers: h,
      body: json !== undefined ? JSON.stringify(json) : body,
      redirect: 'manual',
    })
    const ms = performance.now() - started
    if (cookies) this.store(res)
    const buf = Buffer.from(await res.arrayBuffer())
    const text = buf.toString('utf8')
    let data = text
    try {
      data = JSON.parse(text)
    } catch {
      /* 본문이 JSON 이 아님 */
    }
    return { status: res.status, data, text, headers: res.headers, bytes: buf.length, ms, buf }
  }

  async fetchCsrf() {
    const r = await this.req('GET', '/api/auth/csrf')
    return r.data?.token
  }

  get(path, opts) {
    return this.req('GET', path, opts)
  }
  post(path, json, opts) {
    return this.req('POST', path, { json, ...opts })
  }
  put(path, json, opts) {
    return this.req('PUT', path, { json, ...opts })
  }
  patch(path, json, opts) {
    return this.req('PATCH', path, { json, ...opts })
  }
  del(path, json, opts) {
    return this.req('DELETE', path, { json, ...opts })
  }

  async signup(username = `qa_${uid()}`, name) {
    const r = await this.post('/api/auth/signup', {
      username,
      password: PASSWORD,
      passwordConfirm: PASSWORD,
      name: name ?? username,
      email: `${username}@example.test`,
    })
    if (r.status !== 201) throw new Error(`signup ${username} → ${r.status} ${r.text.slice(0, 200)}`)
    this.username = username
    this.me = (await this.get('/api/auth/me')).data
    this.label = this.label === 'anon' ? username : this.label
    return this
  }

  async login(username, password = PASSWORD) {
    const r = await this.post('/api/auth/login', { username, password })
    if (r.status === 200) this.me = (await this.get('/api/auth/me')).data
    return r
  }

  async upload(folderId, name, content, type = 'application/octet-stream') {
    const form = new FormData()
    form.append('file', new Blob([content], { type }), name)
    if (!this.cookies.has('XSRF-TOKEN')) await this.fetchCsrf()
    const res = await fetch(`${BASE}/api/files/upload?folderId=${folderId}`, {
      method: 'POST',
      headers: { cookie: this.cookieHeader(), 'X-XSRF-TOKEN': this.cookies.get('XSRF-TOKEN') },
      body: form,
    })
    this.store(res)
    const text = await res.text()
    let data = text
    try {
      data = JSON.parse(text)
    } catch {
      /* JSON 아님 */
    }
    return { status: res.status, data, text }
  }

  async createFolder(parentId, name) {
    const r = await this.post('/api/folders', { parentId, name })
    if (r.status !== 201 && r.status !== 200) throw new Error(`folder ${name} → ${r.status} ${r.text}`)
    return r.data
  }

  async createTeam(name) {
    const r = await this.post('/api/teams', { name })
    if (r.status !== 201) throw new Error(`team ${name} → ${r.status} ${r.text}`)
    return r.data
  }

  /** 초대 → 상대가 알림에서 초대 ID 를 찾아 수락 → 권한 설정 */
  async addMember(teamId, other, perms) {
    const inv = await this.post(`/api/teams/${teamId}/invitations`, { username: other.username })
    if (inv.status >= 300) throw new Error(`invite → ${inv.status} ${inv.text}`)
    const n = await other.get('/api/notifications')
    const invitation = n.data.items.find((x) => x.teamId === teamId && x.invitationId)
    const acc = await other.post(`/api/invitations/${invitation.invitationId}/accept`)
    if (acc.status >= 300) throw new Error(`accept → ${acc.status} ${acc.text}`)
    const detail = await this.get(`/api/teams/${teamId}`)
    const member = detail.data.members.find((m) => m.username === other.username)
    if (perms) {
      const p = await this.put(`/api/teams/${teamId}/members/${member.memberId}/permissions`, perms)
      if (p.status >= 300) throw new Error(`perms → ${p.status} ${p.text}`)
    }
    return member
  }
}

/** 결과 표를 마크다운으로 */
export function table(rows, cols) {
  const head = `| ${cols.join(' | ')} |\n|${cols.map(() => '---').join('|')}|\n`
  return head + rows.map((r) => `| ${cols.map((c) => String(r[c] ?? '').replace(/\|/g, '\\|').replace(/\n/g, ' ')).join(' | ')} |`).join('\n')
}
