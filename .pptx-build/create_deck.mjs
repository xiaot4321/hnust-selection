import fs from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const WORKSPACE = 'C:/Users/14426/Desktop/hnust-selection'
const BUILD_DIR = path.join(WORKSPACE, '.pptx-build')
const OUTPUT_DIR = path.join(WORKSPACE, 'presentations-output')
const FINAL_PPTX = path.join(OUTPUT_DIR, '湖南科技大学师生互选系统-项目介绍-实际页面展示版-终稿.pptx')
const NODE_EXE = 'C:/Users/14426/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node.exe'
const NODE_MODULES = 'C:/Users/14426/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules'
const PYTHON_EXE = 'C:/Users/14426/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
const BIN_DIR = 'C:/Users/14426/.cache/codex-runtimes/codex-primary-runtime/dependencies/bin/override'
const SKILL_DIR = 'C:/Users/14426/.codex/plugins/cache/openai-primary-runtime/presentations/26.1007.11041/skills/presentations'
process.env.RUNTIME_NODE = NODE_EXE
process.env.RUNTIME_NODE_MODULES = NODE_MODULES
process.env.CODEX_PRIMARY_RUNTIME_NODE_MODULES = NODE_MODULES
process.env.RUNTIME_PYTHON = PYTHON_EXE
process.env.RUNTIME_BIN_DIR = BIN_DIR
const ARTIFACT_TOOL_URL = pathToFileURL(path.join(NODE_MODULES, '@oai/artifact-tool/dist/artifact_tool.mjs')).href
const { Presentation, PresentationFile } = await import(ARTIFACT_TOOL_URL)

const W = 1280
const H = 720
const C = {
  bg: '#F4F8FA', white: '#FFFFFF', navy: '#123D57', deep: '#10354C', text: '#35566B',
  muted: '#6D8799', blue: '#0982B2', blueLight: '#E8F3F8', mint: '#E8F4F0',
  teal: '#248B85', orange: '#E6A348', line: '#D8E4EB', soft: '#F9FBFC',
}
const FONT = 'Microsoft YaHei'
const presentation = Presentation.create({ slideSize: { width: W, height: H } })

function shape(slide, name, geometry, x, y, width, height, fill = 'none', lineFill = 'none', lineWidth = 0) {
  return slide.shapes.add({
    geometry, name, position: { left: x, top: y, width, height }, fill,
    line: { style: 'solid', fill: lineFill, width: lineWidth },
  })
}

function text(slide, name, value, x, y, width, height, style = {}) {
  const box = shape(slide, name, 'textbox', x, y, width, height)
  box.text = value
  box.text.style = {
    typeface: FONT, fontSize: 18, color: C.text, alignment: 'left',
    verticalAlignment: 'middle', wrap: 'square', ...style,
  }
  return box
}

function card(slide, name, x, y, width, height, fill = C.white, stroke = C.line) {
  return slide.shapes.add({
    geometry: 'roundRect', name, position: { left: x, top: y, width, height },
    fill, line: { style: 'solid', fill: stroke, width: 1 }, borderRadius: 'rounded-xl',
  })
}

function addFooter(slide, page) {
  shape(slide, `footer-rule-${page}`, 'rect', 64, 682, 1152, 1, C.line)
  text(slide, `footer-brand-${page}`, '湖南科技大学师生互选系统 · 项目介绍', 64, 687, 520, 20,
    { fontSize: 10, color: C.muted })
  text(slide, `footer-page-${page}`, String(page).padStart(2, '0'), 1160, 685, 56, 22,
    { fontSize: 11, bold: true, color: C.muted, alignment: 'right' })
}

function addHeading(slide, kicker, title, subtitle) {
  text(slide, `kicker-${title}`, kicker.toUpperCase(), 64, 26, 560, 22,
    { fontSize: 11, bold: true, color: C.blue, wrap: 'none' })
  text(slide, `title-${title}`, title, 64, 49, 1110, 46,
    { fontSize: 30, bold: true, color: C.navy })
  text(slide, `subtitle-${title}`, subtitle, 64, 93, 1110, 30,
    { fontSize: 15, color: C.muted })
}

async function imageBytes(file) {
  const bytes = await fs.readFile(file)
  return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength)
}

async function addScreenshot(slide, name, file, x, y, width, height, alt) {
  card(slide, `${name}-frame`, x - 5, y - 5, width + 10, height + 10, C.white, C.line)
  slide.images.add({
    blob: await imageBytes(file), contentType: 'image/png', alt,
    fit: 'contain', position: { left: x, top: y, width, height },
    geometry: 'roundRect', borderRadius: 'rounded-lg',
  })
}

function setNotes(slide, notes) { slide.speakerNotes.text = notes }

// 1 — Cover
{
  const slide = presentation.slides.add()
  slide.background.fill = C.bg
  shape(slide, 'cover-accent-bar', 'rect', 64, 142, 6, 74, C.blue)
  shape(slide, 'cover-soft-circle', 'ellipse', 825, 28, 405, 405, C.blueLight)
  text(slide, 'cover-university', '湖南科技大学', 88, 143, 420, 44,
    { fontSize: 25, bold: true, color: C.navy })
  text(slide, 'cover-title', '师生互选系统', 88, 194, 450, 84,
    { fontSize: 46, bold: true, color: C.deep })
  text(slide, 'cover-subtitle', '从有序志愿，到逐轮处理与结果跟踪', 88, 303, 448, 54,
    { fontSize: 21, color: C.text })
  text(slide, 'cover-description', '项目介绍 · 学生端 / 导师端 / 管理端', 88, 366, 448, 34,
    { fontSize: 15, color: C.muted })
  card(slide, 'cover-image-frame', 575, 126, 640, 360, C.white, C.line)
  slide.images.add({
    blob: await imageBytes(path.join(BUILD_DIR, 'screenshots', '01-student-preferences.png')),
    contentType: 'image/png', alt: '学生端我的志愿页面截图，演示数据', fit: 'cover',
    position: { left: 584, top: 135, width: 622, height: 342 },
    geometry: 'roundRect', borderRadius: 'rounded-xl',
  })
  card(slide, 'cover-student-tag', 575, 513, 190, 44, C.white, C.line)
  card(slide, 'cover-teacher-tag', 780, 513, 190, 44, C.white, C.line)
  card(slide, 'cover-admin-tag', 985, 513, 230, 44, C.white, C.line)
  text(slide, 'cover-student-label', '学生 · 填报志愿', 590, 514, 160, 40,
    { fontSize: 14, bold: true, color: C.navy, alignment: 'center' })
  text(slide, 'cover-teacher-label', '导师 · 处理申请', 795, 514, 160, 40,
    { fontSize: 14, bold: true, color: C.navy, alignment: 'center' })
  text(slide, 'cover-admin-label', '管理员 · 配置批次', 1002, 514, 196, 40,
    { fontSize: 14, bold: true, color: C.navy, alignment: 'center' })
  text(slide, 'cover-caption', '页面来自项目实际前端组件；页面记录为演示数据。', 575, 581, 640, 25,
    { fontSize: 11, color: C.muted, alignment: 'right' })
  shape(slide, 'cover-bottom-rule', 'rect', 64, 652, 1152, 1, C.line)
  text(slide, 'cover-bottom-label', '项目展示', 64, 664, 300, 24,
    { fontSize: 11, bold: true, color: C.blue })
  text(slide, 'cover-bottom-year', '2026', 1125, 664, 90, 24,
    { fontSize: 12, bold: true, color: C.muted, alignment: 'right' })
  setNotes(slide, '封面页面截图来自 frontend/src/components/student/StudentWorkspace.vue，以合成演示数据运行。项目现状参照用户提供的 AGENTS.md 与 docs/README.md。')
}

// 2 — Editable end-to-end business flow
{
  const slide = presentation.slides.add()
  slide.background.fill = C.bg
  addHeading(slide, 'BUSINESS FLOW', '一条互选流程，依次完成志愿与轮次处理', '学生提交有序志愿；导师对投向本人的申请逐项决定。')
  const steps = [
    ['01', '身份核对', '确认专业与学位'],
    ['02', '志愿提交', '1–3 位不同导师'],
    ['03', '第一轮', '只处理第 1 志愿'],
    ['04', '第二轮', '只处理第 2 志愿'],
    ['05', '第三轮', '只处理第 3 志愿'],
    ['06', '结果', '匹配 / 暂未匹配'],
  ]
  const cardXs = [60, 257, 454, 651, 848, 1045]
  for (let i = 0; i < steps.length; i += 1) {
    const [number, title, detail] = steps[i]
    card(slide, `flow-step-${i + 1}`, cardXs[i], 160, 175, 125, i < 2 ? C.white : C.blueLight,
      i < 2 ? C.line : '#D3E8F0')
    text(slide, `flow-number-${i + 1}`, number, cardXs[i] + 15, 174, 46, 22,
      { fontSize: 12, bold: true, color: i < 2 ? C.blue : C.teal })
    text(slide, `flow-title-${i + 1}`, title, cardXs[i] + 15, 201, 145, 34,
      { fontSize: 19, bold: true, color: C.navy })
    text(slide, `flow-detail-${i + 1}`, detail, cardXs[i] + 15, 240, 145, 30,
      { fontSize: 13, color: C.muted })
    if (i < steps.length - 1) {
      text(slide, `flow-arrow-${i + 1}`, '›', cardXs[i] + 177, 198, 20, 38,
        { fontSize: 28, bold: true, color: C.blue, alignment: 'center' })
    }
  }
  card(slide, 'flow-decision-band', 60, 322, 1150, 82, C.mint, '#D4E9E2')
  text(slide, 'flow-decision-main', '每轮由导师逐项决定：录取 / 不录取', 85, 334, 520, 31,
    { fontSize: 19, bold: true, color: C.navy })
  text(slide, 'flow-decision-detail', '录取时校验导师剩余名额；成功后立即锁定关系，已匹配学生不再进入后续轮次。', 85, 368, 1075, 24,
    { fontSize: 14, color: C.text })
  text(slide, 'flow-outcome-label', '常规轮次后的去向', 60, 425, 320, 25,
    { fontSize: 13, bold: true, color: C.blue })
  const outcomes = [
    { x: 60, width: 340, title: '录取成功', body: '本轮建立并锁定师生关系', fill: C.white, accent: C.teal },
    { x: 470, width: 340, title: '三轮后仍未匹配', body: '按规则转为 UNMATCHED', fill: C.white, accent: C.orange },
    { x: 880, width: 330, title: '批次预先安排补选', body: '进入独立补选窗口，不重跑三轮', fill: C.white, accent: C.blue },
  ]
  outcomes.forEach((item, i) => {
    card(slide, `flow-outcome-${i + 1}`, item.x, 459, item.width, 94, item.fill, C.line)
    shape(slide, `flow-outcome-accent-${i + 1}`, 'rect', item.x, 459, 5, 94, item.accent)
    text(slide, `flow-outcome-title-${i + 1}`, item.title, item.x + 20, 475, item.width - 40, 30,
      { fontSize: 17, bold: true, color: C.navy })
    text(slide, `flow-outcome-body-${i + 1}`, item.body, item.x + 20, 510, item.width - 40, 24,
      { fontSize: 13, color: C.muted })
  })
  text(slide, 'flow-supplement-note', '补选是否安排由管理员在批次发布前决定，并预先配置开放时间。', 880, 562, 330, 30,
    { fontSize: 11, color: C.muted })
  addFooter(slide, 2)
  setNotes(slide, '业务规则来源：requirements.md、docs/business-rules.md、docs/state-machine.md。顺位轮次为本项目分轮志愿流程；导师逐项决定，录取即时锁定关系，不是标准 Gale–Shapley 排序。')
}

function addRoleCallout(slide, prefix, number, heading, body, y) {
  shape(slide, `${prefix}-bubble-${number}`, 'ellipse', 64, y + 2, 34, 34, C.blueLight)
  text(slide, `${prefix}-number-${number}`, number, 64, y + 2, 34, 34,
    { fontSize: 12, bold: true, color: C.blue, alignment: 'center' })
  text(slide, `${prefix}-heading-${number}`, heading, 108, y, 198, 28,
    { fontSize: 16, bold: true, color: C.navy })
  text(slide, `${prefix}-body-${number}`, body, 108, y + 31, 198, 42,
    { fontSize: 12, color: C.muted, verticalAlignment: 'top' })
}

async function addPageSlide({ page, kicker, title, subtitle, image, alt, callouts, note }) {
  const slide = presentation.slides.add()
  slide.background.fill = C.bg
  text(slide, `page-${page}-kicker`, kicker.toUpperCase(), 64, 9, 600, 18,
    { fontSize: 10, bold: true, color: C.blue, wrap: 'none' })
  text(slide, `page-${page}-title`, title, 64, 25, 800, 39,
    { fontSize: 25, bold: true, color: C.navy })
  text(slide, `page-${page}-subtitle`, subtitle, 64, 62, 910, 23,
    { fontSize: 13, color: C.muted })
  card(slide, `page-${page}-demo-tag`, 1010, 49, 206, 30, C.white, C.line)
  text(slide, `page-${page}-demo-tag-text`, `真实页面 · 演示数据   ${String(page).padStart(2, '0')}`, 1018, 49, 190, 30,
    { fontSize: 11, bold: true, color: C.teal, alignment: 'center' })
  await addScreenshot(slide, `page-${page}-screenshot`, path.join(BUILD_DIR, 'screenshots', image), 64, 86, 1152, 624, alt)
  setNotes(slide, `${note}\n截图来自当前项目的 Vue 页面组件；本演示使用合成数据，不含真实学生或导师记录。`)
}

// 3 — Student preferences UI
await addPageSlide({
  page: 3, kicker: 'STUDENT · PREFERENCES', title: '学生端｜选择导师并排列志愿',
  subtitle: '身份核对 → 筛选可报导师 → 选择 1–3 位并保存顺位。', image: '01-student-preferences.png',
  alt: '学生端我的志愿页面，显示导师筛选列表和志愿顺序，演示数据',
  callouts: [
    ['查看可填导师', '按本人专业、学位与导师招生范围显示。'],
    ['加入并排序', '导师卡片标记已加入的志愿顺位。'],
    ['提交当前版本', '每份志愿包含 1–3 位不同导师。'],
  ],
  note: '页面依据：frontend/src/components/student/StudentWorkspace.vue 与 frontend/src/services/studentPortalService.ts。',
})

// 4 — Student progress UI
await addPageSlide({
  page: 4, kicker: 'STUDENT · PROGRESS', title: '学生端｜查看轮次进度与结果',
  subtitle: '查看填报阶段、匹配状态与三轮志愿的逐项进度。', image: '02-student-progress.png',
  alt: '学生端进度与结果页面，显示填报阶段、等待第一轮和三轮状态，演示数据',
  callouts: [
    ['查看当前阶段', '批次状态与当前办理阶段清晰显示。'],
    ['跟进匹配状态', '待处理、等待后续轮次与录取结果分开呈现。'],
    ['核对三轮记录', '按对应志愿顺位查看每轮状态。'],
  ],
  note: '页面依据：frontend/src/components/student/StudentWorkspace.vue。进度文案对应 docs/state-machine.md 与已确认业务规则。',
})

// 5 — Teacher queue UI
await addPageSlide({
  page: 5, kicker: 'TEACHER · REVIEW', title: '导师端｜逐项处理本轮申请',
  subtitle: '仅处理本轮投向本人的申请；名额确认后锁定关系。', image: '03-teacher-round.png',
  alt: '导师工作台常规轮次页面，显示待处理学生与录取、不录取操作，演示数据',
  callouts: [
    ['按轮次查看', '第一、二、三轮分别处理对应顺位。'],
    ['独立做决定', '导师逐项录取或不录取，不对学生排序。'],
    ['成功后锁定关系', '名额校验通过后，匹配关系即时确认。'],
  ],
  note: '页面依据：frontend/src/components/teacher/TeacherWorkspace.vue 与 frontend/src/services/teacherWorkspaceService.ts。',
})

// 6 — Admin batch page
await addPageSlide({
  page: 6, kicker: 'ADMIN · BATCH MANAGEMENT', title: '管理端｜配置批次并跟踪整体进度',
  subtitle: '配置阶段时间与导师名额，按冻结名单跟进批次统计。', image: '04-admin-batch.png',
  alt: '管理员批次管理页面，显示批次阶段、统计数据与名额状态，演示数据',
  callouts: [
    ['管理批次阶段', '维护学生填报、三轮处理及补选排期。'],
    ['查看统计总览', '按填报开窗时冻结的合格名单计算分母。'],
    ['维护导师名额', '查看导师资格、已占名额和剩余名额。'],
  ],
  note: '页面依据：frontend/src/components/admin/BatchManagementPanel.vue 与 frontend/src/services/selectionBatchService.ts。',
})

// 7 — Rules and implementation status
{
  const slide = presentation.slides.add()
  slide.background.fill = C.bg
  addHeading(slide, 'RULES & STATUS', '关键规则与当前实现范围', '页面背后的业务约束统一资格、顺位与名额口径。')
  const rules = [
    { x: 64, number: '01', title: '资格范围', fill: C.white, accent: C.blue,
      lines: ['学生专业与学位类型进入校验。', '导师按批次配置专业与学位范围。', '填报开窗时冻结业务名单与招生范围。'] },
    { x: 455, number: '02', title: '分轮处理', fill: C.blueLight, accent: C.teal,
      lines: ['第 1 / 2 / 3 轮对应第 1 / 2 / 3 志愿。', '导师对投向本人的申请逐项决定。', '录取检查名额并立即锁定关系。'] },
    { x: 846, number: '03', title: '独立补选', fill: C.white, accent: C.orange,
      lines: ['是否安排补选在批次发布前确定。', '补选窗口单独排期，不重跑三轮。', '窗口关闭后结案并完成批次。'] },
  ]
  rules.forEach((item, i) => {
    card(slide, `rule-card-${i + 1}`, item.x, 164, 370, 237, item.fill, C.line)
    shape(slide, `rule-accent-${i + 1}`, 'rect', item.x, 164, 5, 237, item.accent)
    text(slide, `rule-number-${i + 1}`, item.number, item.x + 24, 184, 54, 30,
      { fontSize: 15, bold: true, color: item.accent })
    text(slide, `rule-title-${i + 1}`, item.title, item.x + 24, 219, 305, 36,
      { fontSize: 23, bold: true, color: C.navy })
    item.lines.forEach((line, lineIndex) => {
      shape(slide, `rule-dot-${i + 1}-${lineIndex}`, 'ellipse', item.x + 25, 275 + lineIndex * 35, 7, 7, item.accent)
      text(slide, `rule-line-${i + 1}-${lineIndex}`, line, item.x + 41, 265 + lineIndex * 35, 300, 28,
        { fontSize: 13, color: C.text })
    })
  })
  card(slide, 'status-card', 64, 432, 1152, 92, C.mint, '#D4E9E2')
  text(slide, 'status-label', '当前进展', 88, 447, 120, 24,
    { fontSize: 12, bold: true, color: C.teal })
  text(slide, 'status-main', '学生、导师与管理员的主要业务链路已接入当前代码。', 88, 470, 780, 32,
    { fontSize: 19, bold: true, color: C.navy })
  text(slide, 'status-note', '核心自动化验收与目标环境部署、运维核验仍需继续。', 795, 472, 390, 28,
    { fontSize: 13, color: C.text, alignment: 'right' })
  text(slide, 'tech-label', '技术基线', 64, 563, 120, 24,
    { fontSize: 12, bold: true, color: C.blue })
  const tech = [
    { x: 64, w: 250, text: 'Java 8 · Spring Boot 2.6.6' },
    { x: 330, w: 235, text: 'MySQL 5.7.36' },
    { x: 581, w: 250, text: 'Vue 3 · TypeScript' },
    { x: 847, w: 369, text: '年度资格 · 分轮志愿 · 独立补选' },
  ]
  tech.forEach((item, i) => {
    card(slide, `tech-chip-${i + 1}`, item.x, 594, item.w, 48, C.white, C.line)
    text(slide, `tech-text-${i + 1}`, item.text, item.x + 8, 598, item.w - 16, 38,
      { fontSize: 13, bold: i === 3, color: i === 3 ? C.teal : C.text, alignment: 'center' })
  })
  addFooter(slide, 7)
  setNotes(slide, '项目状态、规则依据：用户提供的 AGENTS.md，docs/README.md、docs/business-rules.md、docs/state-machine.md。技术版本参照 AGENTS.md 与 backend/pom.xml、frontend/package.json。当前代码已接入主要业务链路，不代表自动化验收或生产部署核验已完成。')
}

await fs.mkdir(BUILD_DIR, { recursive: true })
await fs.mkdir(OUTPUT_DIR, { recursive: true })
if (await fs.access(FINAL_PPTX).then(() => true).catch(() => false)) {
  throw new Error(`Refusing to overwrite existing final file: ${FINAL_PPTX}`)
}

// Export editable draft, then run the required validator/finalizer into a new filename.
const candidatePath = path.join(BUILD_DIR, 'candidate.pptx')
await (await PresentationFile.exportPptx(presentation)).save(candidatePath)
const { finalizePresentation } = await import(pathToFileURL(path.join(SKILL_DIR, 'container_tools/artifact_tool_utils.mjs')).href)
const result = await finalizePresentation({
  explicitTotalSlideCount: 7,
  requiredNativeTableOwnerSlides: [],
  requiredNativeChartOwnerSlides: [],
  workspaceDir: WORKSPACE,
  candidatePath,
  finalPath: FINAL_PPTX,
  pythonExecutable: PYTHON_EXE,
  integrityValidatorPath: path.join(SKILL_DIR, 'container_tools/inspect_presentation_package_integrity.py'),
  layoutValidatorPath: path.join(SKILL_DIR, 'container_tools/inspect_presentation_layout_geometry.py'),
  layoutArgs: ['--expected-slide-size-emu', '12192000,6858000', '--validate-bullet-geometry', '--validate-heading-fit'],
  fontPolicy: { basis: 'design', families: [FONT] },
  verifyArtifactToolImport: true,
  receiptPath: path.join(BUILD_DIR, 'validation-page-final.json'),
})
console.log(JSON.stringify({ finalPath: FINAL_PPTX, slideCount: presentation.slides.items.length, validation: result }, null, 2))
