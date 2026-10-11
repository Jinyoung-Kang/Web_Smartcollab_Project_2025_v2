// 개인·팀 드라이브에 점검용 파일을 올리고 미리보기·내려받기·AI 요약·번역·편집·공유 링크·팀 채팅 전송을 브라우저로 확인합니다.
// 실제 Claude 를 부르므로 과금됩니다(요약·번역 약 25건, 1센트 남짓). 데모 모드 임시 스택에서만:
//   APP_PORT=8090 DB_PORT=13308 docker compose -p sc-test up -d --wait
// 실행: node qa/scripts/feature-walkthrough.mjs qa/results/walkthrough   → results.json·화면. 끝나면 docker compose -p sc-test down -v
import { chromium } from '../../e2e/node_modules/playwright/index.mjs'
import { createHash, randomBytes } from 'node:crypto'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { CSV, MEETING, docx, docxParagraphs, pdf, pdfHtml } from './ai-samples.mjs'

const BASE = process.env.QA_BASE_URL ?? 'http://localhost:8090'
process.env.QA_BASE_URL = BASE
const { Client } = await import('./lib.mjs')
const OUT = process.argv[2]
mkdirSync(OUT, { recursive: true })
const AI_TIMEOUT = 90_000
const sha = (b) => createHash('sha256').update(b).digest('hex')

const results = []
const problems = [] // 콘솔 오류·CSP 위반·5xx 응답
let shotNo = 0

const browser = await chromium.launch({ channel: 'chrome' })

function watch(page, who) {
  page.on('console', (m) => { if (m.type() === 'error') problems.push(`[${who}] console: ${m.text().slice(0, 200)}`) })
  page.on('response', (r) => { if (r.status() >= 500) problems.push(`[${who}] ${r.status()} ${r.request().method()} ${r.url()}`) })
}

async function loginDemo(who) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'ko-KR', timezoneId: 'Asia/Seoul', acceptDownloads: true })
  await context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: BASE })
  const page = await context.newPage()
  watch(page, who)
  await page.goto(`${BASE}/login`)
  await page.getByRole('button', { name: new RegExp(who) }).click() // 데모 계정 단추가 아이디·비밀번호를 채움
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await page.waitForURL(/\/drive/)
  return page
}

async function step(page, area, file, item, fn) {
  try {
    const detail = await fn()
    results.push({ area, file, item, ok: true, detail: detail ?? '' })
  } catch (e) {
    const name = `fail-${++shotNo}.png`
    await page.screenshot({ path: `${OUT}/${name}` }).catch(() => {})
    results.push({ area, file, item, ok: false, detail: `${String(e.message).split('\n')[0].slice(0, 220)} (${name})` })
    await page.keyboard.press('Escape').catch(() => {})
  }
}

// ── 점검용 파일 ──
const shotPage = await browser.newPage()
await shotPage.setContent('<body style="margin:0;background:#2563eb;color:#fff;font:bold 40px sans-serif;display:grid;place-items:center;height:240px">점검 사진</body>')
const png = await shotPage.screenshot({ clip: { x: 0, y: 0, width: 400, height: 240 } })
await shotPage.close()
const json = JSON.stringify({ title: '알림 설정', message: '새 버전이 저장되면 팀에 알립니다.', enabled: true }, null, 2) + '\n'
const FILES = [
  { key: 'pdf', name: '보고서.pdf', type: 'application/pdf', body: Buffer.from(await pdf(pdfHtml)), kind: 'PDF', lang: ['EN', '영어'] },
  { key: 'docx', name: '입사 안내.docx', type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', body: docx(docxParagraphs), kind: 'DOCX', lang: ['ZH', '중국어(간체)'] },
  { key: 'csv', name: '요구사항.csv', type: 'text/csv', body: Buffer.from(CSV), kind: 'TEXT', expect: '공유 링크 만료 단축', lang: ['JA', '일본어'] },
  { key: 'md', name: '회의록.md', type: 'text/markdown', body: Buffer.from(MEETING), kind: 'TEXT', expect: '3분기 제품 회의록', lang: ['EN', '영어'] },
  { key: 'json', name: '설정.json', type: 'application/json', body: Buffer.from(json), kind: 'TEXT', expect: '알림 설정', lang: ['JA', '일본어'] },
  { key: 'png', name: '사진.png', type: 'image/png', body: png, kind: 'IMAGE' },
]
for (const f of FILES) f.sha = sha(f.body)

async function openPreview(page, f) {
  await page.getByRole('button', { name: f.name, exact: true }).click()
  const dialog = page.getByRole('dialog', { name: f.name })
  await dialog.waitFor()
  return dialog
}

async function fileIdOf(dialog) {
  const href = await dialog.getByRole('link', { name: '내려받기' }).getAttribute('href')
  return Number(href.match(/\/api\/files\/(\d+)\/download/)[1])
}

async function downloadVia(page, locator) {
  const [d] = await Promise.all([page.waitForEvent('download'), locator.click()])
  const path = await d.path()
  return { name: d.suggestedFilename(), body: readFileSync(path) }
}

async function driveChecks(page, area, ids) {
  for (const f of FILES) {
    await step(page, area, f.name, '미리보기', async () => {
      const dialog = await openPreview(page, f)
      ids[f.key] = await fileIdOf(dialog)
      if (f.kind === 'PDF') {
        const src = await dialog.locator('iframe').getAttribute('src')
        const r = await page.evaluate(async (u) => { const res = await fetch(u); return [res.status, res.headers.get('content-type')] }, src)
        if (r[0] !== 200 || !r[1].includes('pdf')) throw new Error(`PDF 보기 응답 ${r}`)
        return `내장 뷰어(iframe), ${r[1]}`
      }
      if (f.kind === 'IMAGE') {
        const img = dialog.getByRole('img', { name: f.name })
        await img.waitFor()
        await page.waitForFunction((el) => el.naturalWidth > 0, await img.elementHandle())
        return `이미지 ${await img.evaluate((el) => `${el.naturalWidth}×${el.naturalHeight}`)}`
      }
      if (f.kind === 'DOCX') {
        await dialog.getByText('Office 미리보기를 지원하지 않습니다').waitFor()
        return '로컬 저장소라 Office 뷰어 대신 안내(설계대로) · 요약·번역 도구 표시'
      }
      await dialog.getByText(f.expect).first().waitFor()
      return `본문 표시("${f.expect}")`
    })

    await step(page, area, f.name, '내려받기', async () => {
      const dialog = page.getByRole('dialog', { name: f.name })
      const d = await downloadVia(page, dialog.getByRole('link', { name: '내려받기' }))
      if (d.name !== f.name) throw new Error(`파일 이름 ${d.name}`)
      if (sha(d.body) !== f.sha) throw new Error('내용이 올린 파일과 다름')
      return `이름·SHA-256 일치 (${d.body.length.toLocaleString()} B)`
    })

    if (f.kind === 'IMAGE') {
      await step(page, area, f.name, '요약·번역 도구 없음', async () => {
        const dialog = page.getByRole('dialog', { name: f.name })
        if (await dialog.getByRole('button', { name: /AI 요약|핵심 문장/ }).count()) throw new Error('이미지에 요약 단추가 있음')
        return '이미지에는 단추가 없음(설계대로)'
      })
    } else {
      await step(page, area, f.name, 'AI 요약', async () => {
        const dialog = page.getByRole('dialog', { name: f.name })
        await dialog.getByRole('button', { name: /AI 요약/ }).click()
        const panel = dialog.getByRole('complementary', { name: '요약·번역' })
        await panel.getByText(/만든 요약/).waitFor({ timeout: AI_TIMEOUT })
        const text = (await panel.innerText()).split('\n').filter(Boolean)
        return text[1]?.slice(0, 70)
      })
      await step(page, area, f.name, `번역(${f.lang[1]})`, async () => {
        const dialog = page.getByRole('dialog', { name: f.name })
        await dialog.getByRole('combobox', { name: '번역 언어' }).selectOption(f.lang[0])
        await dialog.getByRole('button', { name: '번역', exact: true }).click()
        const panel = dialog.getByRole('complementary', { name: '요약·번역' })
        await panel.getByText(`번역 결과 (${f.lang[1]})`).waitFor({ timeout: AI_TIMEOUT })
        await panel.getByText(/번역했습니다/).waitFor({ timeout: AI_TIMEOUT })
        const body = (await panel.innerText()).split('\n').filter(Boolean)
        const translated = body.slice(1, -1).join(' / ')
        if (/wait,|let me|correct output|<document|```/i.test(translated)) throw new Error(`결과에 군더더기: ${translated.slice(0, 80)}`)
        if (f.key === 'csv') {
          const rows = body.slice(1, -1).length
          if (rows !== CSV.trim().split('\n').length) throw new Error(`CSV 행 수 ${rows}`)
        }
        return translated.slice(0, 70)
      })
      if (f.key === 'pdf' || f.key === 'docx') await page.screenshot({ path: `${OUT}/${area}-${f.key}-ai.png` })
    }
    await page.keyboard.press('Escape')
    await page.getByRole('dialog', { name: f.name }).waitFor({ state: 'hidden' })
  }

  // 편집: 미리보기 → 편집기 → 저장 → 버전 기록 → 새 버전 요약 → 내려받은 내용에 반영
  const md = FILES.find((f) => f.key === 'md')
  const line = `점검 편집 ${Date.now()}`
  await step(page, area, md.name, '편집·저장(새 버전)', async () => {
    const dialog = await openPreview(page, md)
    await dialog.getByRole('button', { name: '편집기로 열기' }).click()
    await page.waitForURL(/\/edit$/)
    const editor = page.getByLabel('문서 내용')
    await editor.click()
    await editor.press('ControlOrMeta+End')
    await editor.pressSequentially(`\n${line}`)
    await page.getByRole('button', { name: '저장', exact: true }).click()
    await page.getByText('새 버전으로 저장했습니다.').waitFor()
    await page.getByRole('button', { name: '버전', exact: true }).click()
    const history = page.getByRole('dialog', { name: '버전 기록' })
    await history.getByText('현재 버전', { exact: true }).waitFor()
    const versions = await history.getByRole('listitem').count()
    await page.keyboard.press('Escape')
    return `저장 알림·버전 기록 ${versions}개`
  })
  await step(page, area, md.name, '편집기에서 AI 요약(새 버전)', async () => {
    await page.getByRole('button', { name: /AI 요약/ }).click()
    const panel = page.getByRole('complementary', { name: '요약·번역' })
    await panel.getByText(/만든 요약/).waitFor({ timeout: AI_TIMEOUT })
    return (await panel.innerText()).split('\n').filter(Boolean)[1]?.slice(0, 70)
  })
  await step(page, area, md.name, '편집 뒤 내려받기에 반영', async () => {
    const text = await page.evaluate(async (id) => (await fetch(`/api/files/${id}/download`)).text(), ids.md)
    if (!text.includes(line)) throw new Error('편집한 줄이 없음')
    return '편집한 줄 포함'
  })
  await page.getByRole('button', { name: '편집기 닫기' }).click()
  await page.getByRole('button', { name: md.name, exact: true }).waitFor()

  // 외부 공유 링크(비밀번호·1회): 링크 → 로그인 없이 비밀번호로 내려받기 → 두 번째는 막힘
  const shared = FILES.find((f) => f.key === 'pdf')
  const sharePassword = randomBytes(9).toString('base64url')
  let linkPath
  await step(page, area, shared.name, '공유 링크 만들기(비밀번호·1회)', async () => {
    await page.getByLabel(`${shared.name} 선택`).check()
    await page.getByRole('toolbar').getByRole('button', { name: '공유' }).click()
    const dialog = page.getByRole('dialog')
    await dialog.getByPlaceholder('4자 이상').fill(sharePassword)
    await dialog.getByPlaceholder('제한 없음').fill('1')
    await dialog.getByRole('button', { name: /링크 만들고 복사/ }).click()
    linkPath = (await dialog.locator('p.font-mono').first().textContent()).trim()
    await page.keyboard.press('Escape')
    await page.getByLabel(`${shared.name} 선택`).uncheck()
    if (!linkPath.includes('/share/')) throw new Error(`링크 ${linkPath}`)
    return '링크 생성·복사'
  })
  await step(page, area, shared.name, '로그인 없이 링크로 내려받기', async () => {
    const anon = await (await browser.newContext({ acceptDownloads: true })).newPage()
    watch(anon, 'anonymous')
    await anon.goto(new URL(linkPath, BASE).href)
    await anon.getByText(shared.name).first().waitFor()
    await anon.locator('input[type=password]').fill('wrong-pass')
    await anon.getByRole('button', { name: /확인하고 내려받기/ }).click()
    await anon.getByRole('alert').waitFor()
    const wrong = (await anon.getByRole('alert').innerText()).slice(0, 40)
    await anon.locator('input[type=password]').fill(sharePassword)
    const d = await downloadVia(anon, anon.getByRole('button', { name: /확인하고 내려받기/ }))
    if (sha(d.body) !== shared.sha) throw new Error('내용 불일치')
    if (area === '팀') await anon.screenshot({ path: `${OUT}/share-page.png` })
    await anon.reload()
    await anon.waitForTimeout(800)
    const after = (await anon.locator('main').innerText()).replace(/\s+/g, ' ').slice(0, 120)
    await anon.context().close()
    if (after.includes('확인하고 내려받기')) throw new Error(`1회 제한 뒤에도 내려받기 가능: ${after}`)
    return `틀린 비밀번호 거절("${wrong}") · 내려받기 SHA 일치 · 1회 뒤 "${after.slice(0, 50)}"`
  })
}

// ── 개인 드라이브 ──
const stamp = Date.now()
const kim = await loginDemo('김하늘')
const personalIds = {}
const teamIds = {}

async function prepareFolder(page, area, folder) {
  await step(page, area, '(6개)', '폴더 만들기·여러 파일 올리기', async () => {
    await page.getByRole('button', { name: '새 폴더' }).click()
    await page.getByRole('dialog').getByRole('textbox').fill(folder)
    await page.getByRole('dialog').getByRole('button', { name: '만들기' }).click()
    await page.getByRole('button', { name: folder, exact: true }).click()
    await page.getByRole('heading', { level: 1, name: folder }).waitFor()
    await page.locator('input[type=file][multiple]').setInputFiles(FILES.map((f) => ({ name: f.name, mimeType: f.type, buffer: f.body })))
    for (const f of FILES) await page.getByRole('button', { name: f.name, exact: true }).waitFor({ timeout: 30_000 })
    return FILES.map((f) => f.name).join(', ')
  })
}

await prepareFolder(kim, '개인', `기능점검-${stamp}`)
await driveChecks(kim, '개인', personalIds)

// ── 팀 드라이브 ──
await kim.getByRole('link', { name: /SmartCollab 데모 팀/ }).click()
await kim.waitForURL(/\/teams\/\d+\/folders\/\d+/)
await prepareFolder(kim, '팀', `기능점검-${stamp}`)
await driveChecks(kim, '팀', teamIds)

// ── 팀 채팅으로 보내기: 메시지·파일 첨부 → 다른 멤버가 받고 미리보기·요약·내려받기 ──
const lee = await loginDemo('이도윤')
await lee.getByRole('link', { name: /SmartCollab 데모 팀/ }).click()
await lee.waitForURL(/\/teams\/\d+\/folders\/\d+/)
const chatText = `기능 점검 메시지 ${stamp}`
await step(kim, '팀 채팅', '-', '메시지 보내기 → 다른 멤버가 실시간 수신', async () => {
  await kim.getByLabel('메시지').fill(chatText)
  await kim.getByLabel('메시지').press('Enter')
  await lee.getByText(chatText).waitFor({ timeout: 10_000 })
  return '이도윤 화면에 표시'
})
const chatFile = { ...FILES.find((f) => f.key === 'docx'), name: `채팅-입사 안내-${stamp}.docx` }
await step(kim, '팀 채팅', chatFile.name, '파일 첨부해 보내기 → 다른 멤버 수신', async () => {
  await kim.locator('form', { has: kim.getByLabel('메시지') }).locator('input[type=file]')
    .setInputFiles({ name: chatFile.name, mimeType: chatFile.type, buffer: chatFile.body })
  await kim.getByRole('button', { name: `${chatFile.name} 미리보기` }).waitFor({ timeout: 30_000 })
  await lee.getByRole('button', { name: `${chatFile.name} 미리보기` }).waitFor({ timeout: 10_000 })
  return '보낸 사람·받는 사람 모두 파일 카드 표시'
})
await step(lee, '팀 채팅', chatFile.name, '받은 파일 미리보기·AI 요약(팀 멤버)', async () => {
  await lee.getByRole('button', { name: `${chatFile.name} 미리보기` }).click()
  const dialog = lee.getByRole('dialog', { name: chatFile.name })
  await dialog.getByRole('button', { name: /AI 요약/ }).click()
  const panel = dialog.getByRole('complementary', { name: '요약·번역' })
  await panel.getByText(/만든 요약/).waitFor({ timeout: AI_TIMEOUT })
  await lee.screenshot({ path: `${OUT}/chat-received-summary.png` })
  const s = (await panel.innerText()).split('\n').filter(Boolean)[1]?.slice(0, 60)
  await lee.keyboard.press('Escape')
  return s
})
await step(lee, '팀 채팅', chatFile.name, '받은 파일 내려받기', async () => {
  const d = await downloadVia(lee, lee.getByRole('link', { name: `${chatFile.name} 내려받기` }))
  if (sha(d.body) !== chatFile.sha) throw new Error('내용 불일치')
  return `SHA 일치 (${d.name})`
})
await kim.screenshot({ path: `${OUT}/chat-sent.png` })

// ── 권한: 팀 밖 사용자는 팀 파일을 볼 수도, AI 로 보낼 수도 없음 ──
await step(kim, '권한', FILES[0].name, '팀 밖 사용자 접근(내려받기·요약·번역)', async () => {
  const outsider = await new Client('outsider').signup()
  const codes = []
  for (const [m, p] of [['GET', `/api/files/${teamIds.pdf}/download`], ['POST', `/api/files/${teamIds.pdf}/summary`], ['POST', `/api/files/${teamIds.pdf}/translation?target=EN`], ['GET', `/api/files/${personalIds.md}/content`]]) {
    codes.push((await outsider.req(m, p)).status)
  }
  if (codes.some((c) => c !== 404)) throw new Error(`응답 ${codes}`)
  return `모두 404 (${codes.join(', ')})`
})

await browser.close()
writeFileSync(`${OUT}/results.json`, JSON.stringify({ results, problems }, null, 2))
const failed = results.filter((r) => !r.ok)
console.log(`항목 ${results.length}개, 실패 ${failed.length}개, 콘솔·5xx 문제 ${problems.length}개`)
for (const r of results) console.log(`${r.ok ? 'PASS' : 'FAIL'} | ${r.area} | ${r.file} | ${r.item} | ${r.detail}`)
for (const p of problems) console.log('PROBLEM', p)
