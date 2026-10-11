import { mkdir } from 'node:fs/promises'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawn } from 'node:child_process'
import net from 'node:net'

const { chromium } = await import('file:///C:/Users/14426/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.mjs')
const out = fileURLToPath(new URL('./screenshots/', import.meta.url))
await mkdir(out, { recursive: true })
const port = 5273
const baseUrl = `http://127.0.0.1:${port}`
const vite = spawn(process.execPath, ['frontend/node_modules/vite/bin/vite.js', 'frontend', '--host', '127.0.0.1', '--port', String(port), '--strictPort'], {
  cwd: resolve(out, '..', '..'), stdio: ['ignore', 'ignore', 'pipe'], windowsHide: true,
})
let viteLogs = ''
vite.stderr.on('data', (chunk) => { viteLogs += chunk.toString().slice(0, 1000) })
async function waitForVite() {
  for (let i = 0; i < 60; i += 1) {
    if (vite.exitCode !== null) throw new Error(`Vite exited (${vite.exitCode}): ${viteLogs}`)
    const available = await new Promise((resolvePromise) => {
      const socket = net.connect(port, '127.0.0.1')
      socket.once('connect', () => { socket.destroy(); resolvePromise(true) })
      socket.once('error', () => resolvePromise(false))
    })
    if (available) return
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 250))
  }
  throw new Error(`Vite did not start: ${viteLogs}`)
}
await waitForVite()
const browser = await chromium.launch({ headless: true, executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', args: ['--no-proxy-server', '--proxy-bypass-list=*'] })
const date = '2026-10-10T09:00:00Z'
const pageResult = (items) => ({ items, total: items.length, pageNo: 1, pageSize: 100 })
const profile = {
  studentId: 1001, studentNo: '202600101', fullName: '林同学', collegeId: 1, collegeName: '计算机科学与工程学院',
  major: { majorId: 11, majorCode: '080901', majorName: '计算机科学与技术' }, degreeType: 'ACADEMIC_MASTER',
  classificationVersion: 2, accountStatus: 'ACTIVE', profileVersion: 1, profileEtag: '"student-1"',
  biography: '', contact: '', resume: null,
}
const batchSummary = {
  batchId: 301, batchName: '2026级研究生师生互选', academicYearId: 26, academicYearName: '2026级',
  batchStatus: 'ACTIVE', supplementPlanned: true,
  currentStage: { stageCode: 'FILLING', stageStatus: 'OPEN', startAt: date, endAt: '2026-10-20T09:00:00Z' },
  preferenceStatus: 'SUBMITTED', matchStatus: 'PENDING_ROUND_1', matchReason: null,
  identityClassificationVersion: 2, confirmedClassificationVersion: 2,
  actions: { canConfirmIdentity: false, canSubmitPreferences: true, canWithdrawPreferences: true, canApplySupplement: false },
}
const teachers = [
  { teacherId: 201, employeeNo: 'T0108', displayName: '周老师', researchDirections: ['人工智能', '机器学习'], profileSummary: '关注智能计算与数据分析。',
    allowedDegreeTypes: ['ACADEMIC_MASTER'], allowedMajors: [{ majorId: 11, majorCode: '080901', majorName: '计算机科学与技术' }],
    canApply: true, avatarUrl: null, officialProfile: null },
  { teacherId: 202, employeeNo: 'T0126', displayName: '陈老师', researchDirections: ['软件工程', '可信系统'], profileSummary: '关注软件质量与可信计算。',
    allowedDegreeTypes: ['ACADEMIC_MASTER', 'PROFESSIONAL_MASTER'], allowedMajors: [{ majorId: 11, majorCode: '080901', majorName: '计算机科学与技术' }],
    canApply: true, avatarUrl: null, officialProfile: null },
  { teacherId: 203, employeeNo: 'T0141', displayName: '刘老师', researchDirections: ['计算机视觉'], profileSummary: '关注视觉理解与多模态学习。',
    allowedDegreeTypes: ['ACADEMIC_MASTER'], allowedMajors: [{ majorId: 11, majorCode: '080901', majorName: '计算机科学与技术' }],
    canApply: true, avatarUrl: null, officialProfile: null },
]
const preferences = {
  preferenceStatus: 'SUBMITTED', submissionId: 7701, versionNo: 1, submittedAt: date, lockedAt: null,
  items: [
    { teacherId: 201, preferenceOrder: 1, teacherName: '周老师', employeeNo: 'T0108', researchDirections: ['人工智能', '机器学习'] },
    { teacherId: 202, preferenceOrder: 2, teacherName: '陈老师', employeeNo: 'T0126', researchDirections: ['软件工程', '可信系统'] },
    { teacherId: 203, preferenceOrder: 3, teacherName: '刘老师', employeeNo: 'T0141', researchDirections: ['计算机视觉'] },
  ],
}
const studentProgress = {
  batchStatus: 'ACTIVE', currentStage: batchSummary.currentStage, preferenceStatus: 'SUBMITTED',
  matchStatus: 'PENDING_ROUND_1', matchReason: null, currentRelation: null,
  rounds: [1, 2, 3].map((n) => ({ roundNo: n, preferenceOrder: n, teacherId: preferences.items[n - 1].teacherId,
    teacherName: preferences.items[n - 1].teacherName, state: n === 1 ? 'PENDING' : 'WAITING', resultPublished: false, processedAt: null })),
}
const teacherProfile = {
  teacherId: 201, employeeNo: 'T0108', fullName: '周老师', publishedResearchDirections: '人工智能；机器学习',
  publishedBiography: '主要从事智能计算与数据分析研究。', publishedEducationExperience: null,
  publishedWorkExperience: null, publishedCourses: null, publishedResearchAndAchievements: null,
  reviewStatus: 'APPROVED', reviewComment: null, submittedResearchDirections: '人工智能；机器学习',
  submittedBiography: '主要从事智能计算与数据分析研究。', submittedEducationExperience: null,
  submittedWorkExperience: null, submittedCourses: null, submittedResearchAndAchievements: null,
  publishedAvatarUrl: null, submittedAvatarUrl: null, versionNo: 1, etag: '"teacher-profile-1"',
}
const teacherApps = [
  { applicationId: 8001, batchId: 301, batchName: batchSummary.batchName, roundNo: 1, preferenceOrder: 1,
    studentNo: '202600101', fullName: '林同学', majorName: '计算机科学与技术', degreeType: 'ACADEMIC_MASTER',
    biography: '关注机器学习与智能应用，希望参与相关课题研究。', resumeFileId: null, status: 'IN_REVIEW', enteredReviewAt: date, processedAt: null },
  { applicationId: 8002, batchId: 301, batchName: batchSummary.batchName, roundNo: 1, preferenceOrder: 1,
    studentNo: '202600118', fullName: '许同学', majorName: '计算机科学与技术', degreeType: 'PROFESSIONAL_MASTER',
    biography: '希望在智能系统方向开展实践研究。', resumeFileId: null, status: 'IN_REVIEW', enteredReviewAt: date, processedAt: null },
]
const stages = [
  ['FILLING', 1, '2026-10-10T09:00:00Z', '2026-10-20T09:00:00Z'],
  ['ROUND_1', 2, '2026-10-21T09:00:00Z', '2026-10-24T09:00:00Z'],
  ['ROUND_2', 3, '2026-10-25T09:00:00Z', '2026-10-28T09:00:00Z'],
  ['ROUND_3', 4, '2026-10-29T09:00:00Z', '2026-11-01T09:00:00Z'],
  ['SUPPLEMENT', 5, '2026-11-02T09:00:00Z', '2026-11-06T09:00:00Z'],
].map(([stageCode, stageOrder, start, end], index) => ({ id: index + 1, stageCode, stageOrder, status: index === 0 ? 'OPEN' : 'NOT_STARTED',
  closeReason: null, plannedStartAt: start, plannedEndAt: end, effectiveStartAt: index === 0 ? start : null, effectiveEndAt: null }))
const batch = { id: 301, collegeId: 1, collegeName: '计算机科学与工程学院', academicYearId: 26, yearCode: '2026',
  batchCode: '2026-A', name: batchSummary.batchName, status: 'ACTIVE', supplementPlanned: true, appendReason: null,
  publishedAt: '2026-10-01T09:00:00Z', startedAt: date, rowVersion: 4 }
const batchDetail = { batch, stages }
const statistics = { batchId: 301, frozenDenominator: 128, matchedCount: 46, currentUnmatchedCount: 82,
  normalPreferenceUnmatchedCount: 0, notSubmittedCount: 0, allRemainingPreferencesSkippedCount: 0,
  identityCorrectionUnmatchedCount: 0, relationCorrectionUnmatchedCount: 0, batchCancelledUnmatchedCount: 0,
  supplementAdmittedCount: 0, supplementStillUnmatchedCount: 82, quotaLimit: 88, occupiedQuota: 46, remainingQuota: 42,
  rounds: [1, 2, 3].map((n) => ({ roundNo: n, stageStatus: n === 1 ? 'PROCESSING' : 'NOT_STARTED',
    pendingApplicationCount: n === 1 ? 52 : 0, admittedCount: n === 1 ? 46 : 0, notAdmittedCount: 0, skippedStudentCount: 0, cancelledApplicationCount: 0 })) }
const quotas = [
  { teacherId: 201, employeeNo: 'T0108', fullName: '周老师', profileStatus: 'PUBLISHED', eligibilityBasis: '年度资格有效', quotaLimit: 8, occupiedCount: 5, remainingCount: 3, rowVersion: 2, scopeConfigured: true, scopeFrozen: true },
  { teacherId: 202, employeeNo: 'T0126', fullName: '陈老师', profileStatus: 'PUBLISHED', eligibilityBasis: '年度资格有效', quotaLimit: 6, occupiedCount: 3, remainingCount: 3, rowVersion: 1, scopeConfigured: true, scopeFrozen: true },
]
const college = { id: 1, code: 'CS', name: '计算机科学与工程学院', canCreateBatch: true }
const academicYears = [{ id: 26, yearCode: '2026', displayName: '2026级' }]
const majors = [{ id: 11, collegeId: 1, majorCode: '080901', name: '计算机科学与技术', active: true }]
const emailStatus = { email: 'demo@example.edu.cn', configured: true }

async function dataFor(path) {
  const pathname = path.split('?')[0]
  if (path === '/auth/email') return emailStatus
  if (path === '/students/me') return profile
  if (path.startsWith('/students/me/batches/') && path.endsWith('/progress')) return studentProgress
  if (path.startsWith('/students/me/batches/') && path.endsWith('/preferences')) return preferences
  if (path.startsWith('/students/me/batches/') && path.endsWith('/preference-submissions')) return pageResult([preferences])
  if (path === '/students/me/batches' || path.startsWith('/students/me/batches?')) return pageResult([batchSummary])
  if (path.startsWith('/teachers?')) return pageResult(teachers)
  if (path.startsWith('/teachers/me/application-scopes/batches')) return [{ batchId: 301, collegeId: 1, collegeName: '计算机科学与工程学院',
    academicYearCode: '2026', batchCode: '2026-A', name: batchSummary.batchName, status: 'ACTIVE', fillingStartAt: date, scopeFrozen: true }]
  if (path === '/teachers/me/profile') return teacherProfile
  if (pathname.startsWith('/teachers/me/batches/') && pathname.includes('/rounds/') && pathname.endsWith('/applications')) return pageResult(teacherApps)
  if (path.endsWith('/summary') && path.includes('/teachers/me/batches/')) return { batchId: 301, batchName: batchSummary.batchName,
    batchStatus: 'ACTIVE', currentStage: 'ROUND_1', quotaLimit: 8, occupiedCount: 5, remainingCount: 3, matchedStudents: [] }
  if (path === '/admin/selection-batches/colleges') return [college]
  if (path.startsWith('/admin/selection-batches/academic-years')) return academicYears
  if (path === '/admin/selection-batches?collegeId=1' || path === '/admin/selection-batches') return [batch]
  if (path === '/admin/selection-batches/301') return batchDetail
  if (path === '/admin/selection-batches/301/majors') return majors
  if (path === '/admin/selection-batches/301/teachers') return quotas
  if (path === '/admin/selection-batches/301/statistics') return statistics
  if (path.startsWith('/majors?')) return pageResult([{ majorId: 11, majorCode: '080901', majorName: '计算机科学与技术', active: true }])
  if (path.startsWith('/students/me/') || path.startsWith('/teachers/me/')) return pageResult([])
  return pageResult([])
}

async function capture(mode, filename, sectionText = null) {
  const page = await browser.newPage({ viewport: { width: 1600, height: 900 }, deviceScaleFactor: 1 })
  const failures = []
  page.on('pageerror', (e) => failures.push(e.message))
  page.on('console', (message) => { if (message.type() === 'error') failures.push(`console: ${message.text()}`) })
  page.on('response', async (response) => {
    if (response.status() >= 400) failures.push(`HTTP ${response.status()} ${response.url()}`)
    const contentType = response.headers()['content-type'] ?? ''
    if (contentType.includes('application/json')) failures.push(`JSON response ${response.status()} ${response.url()} ${String(await response.text().catch(() => '')).slice(0, 400)}`)
  })
  page.on('requestfailed', (req) => failures.push(`${req.method()} ${req.url()} ${req.failure()?.errorText ?? ''}`))
  await page.route(`${baseUrl}/api/**`, async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname.replace(/^\/api/, '') + url.search
    try {
      const data = await dataFor(path)
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 'OK', message: '操作成功', data }) })
    } catch (error) {
      failures.push(`mock ${path} ${error instanceof Error ? error.message : String(error)}`)
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 'OK', message: '操作成功', data: pageResult([]) }) })
    }
  })
  await page.goto(`${baseUrl}/ppt-preview.html?view=${mode}`, { waitUntil: 'networkidle' })
  await page.waitForTimeout(900)
  await page.locator('.email-security-panel').evaluateAll((elements) => elements.forEach((element) => element.remove()))
  if (sectionText) {
    try {
      await page.getByText(sectionText, { exact: true }).first().click({ timeout: 5000 })
      await page.waitForTimeout(500)
    } catch (error) {
      failures.push(`could not open section ${sectionText}: ${error instanceof Error ? error.message : String(error)}`)
    }
  }
  if (mode === 'student' || mode === 'teacher') {
    const selector = mode === 'student' ? '.student-heading' : '.teacher-dashboard-header'
    await page.locator(selector).evaluate((element) => {
      window.scrollTo(0, element.getBoundingClientRect().top + window.scrollY + 25)
    })
    await page.waitForTimeout(150)
  }
  const bodyText = (await page.locator('body').innerText()).replace(/\n{3,}/g, '\n\n')
  const title = await page.title()
  const html = (await page.content()).slice(0, 800)
  const moduleProbe = await page.evaluate(async () => {
    const response = await fetch('/ppt-preview.ts')
    return { status: response.status, type: response.headers.get('content-type'), body: (await response.text()).slice(0, 1000) }
  })
  await page.screenshot({ path: resolve(out, filename), fullPage: false })
  await page.close()
  return { mode, title, bodyText: bodyText.slice(0, 1600), html, moduleProbe, failures }
}

const results = []
results.push(await capture('student', '01-student-preferences.png', '我的志愿'))
results.push(await capture('student', '02-student-progress.png', '进度与结果'))
results.push(await capture('teacher', '03-teacher-round.png'))
results.push(await capture('admin', '04-admin-batch.png'))
console.log(JSON.stringify(results, null, 2))
await browser.close()
vite.kill()
