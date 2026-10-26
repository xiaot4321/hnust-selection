<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ApiError } from '../../api/http'
import {
  personnelManagementService,
  type AcademicYearOption,
  type EligibilityRecord,
  type PersonRecord,
} from '../../services/personnelManagementService'

const props = defineProps<{ collegeId: number; collegeName: string; years: AcademicYearOption[] }>()
const emit = defineEmits<{ busy: [value: boolean]; academicYearCreated: [year: AcademicYearOption] }>()
type QualificationMode = 'STUDENT' | 'TEACHER'
type TeacherBatchOutcome = 'PROCESSING' | 'SUCCESS' | 'UNCHANGED' | 'FAILED'
interface TeacherFilterSnapshot { collegeId: number; academicYearId: number; keyword: string }
interface TeacherBatchCommand {
  eligibilityStatus: 'ELIGIBLE' | 'INELIGIBLE'
  evidenceType: string
  evidenceReference: string
}
interface TeacherBatchResult {
  person: PersonRecord
  outcome: TeacherBatchOutcome
  message: string
  command: TeacherBatchCommand
}
type Candidate = PersonRecord & { status: 'READY' | 'EXISTING' | 'SUCCESS' | 'FAILED'; failure: string }

const mode = ref<QualificationMode>('STUDENT')
const yearId = ref<number | null>(null)
const yearFormOpen = ref(false)
const yearSaving = ref(false)
const yearError = ref('')
const yearDraft = ref({ yearCode: '', displayName: '', changeBasis: '' })
const enrollmentYear = ref('')
const basis = ref('')
const teacherKeyword = ref('')
const teacherStatus = ref<'ELIGIBLE' | 'INELIGIBLE'>('ELIGIBLE')
const teacherEvidenceType = ref('ROSTER_IMPORT')
const teacherEvidenceReference = ref('')
const loading = ref(false)
const saving = ref(false)
const historyVisible = ref(false)
const historyRows = ref<EligibilityRecord[]>([])
const previewed = ref(false)
const error = ref('')
const result = ref('')
const rows = ref<Candidate[]>([])
const teacherPeople = ref<PersonRecord[]>([])
const teacherPeopleTotal = ref(0)
const teacherListFailed = ref(false)
const teacherPageNo = ref(1)
const selectedTeachers = ref<PersonRecord[]>([])
const appliedTeacherFilters = ref<TeacherFilterSnapshot | null>(null)
const teacherEligibilityById = ref<Record<number, EligibilityRecord>>({})
const teacherBatchResults = ref<TeacherBatchResult[]>([])
const batchProgress = ref(0)
const batchTotal = ref(0)
let studentContextVersion = 0
let teacherRequestVersion = 0

const busy = computed(() => loading.value || saving.value)
const pendingCount = computed(() => rows.value.filter(row => row.status === 'READY' || row.status === 'FAILED').length)
const teacherPageCount = computed(() => Math.max(1, Math.ceil(teacherPeopleTotal.value / 20)))
const selectedTeacherIds = computed(() => new Set(selectedTeachers.value.map(person => person.id)))
const allTeacherPageSelected = computed(() => teacherPeople.value.length > 0
  && teacherPeople.value.every(person => selectedTeacherIds.value.has(person.id)))
function sameTeacherFilters(current: TeacherFilterSnapshot | null, expected: TeacherFilterSnapshot): boolean {
  return current !== null && current.collegeId === expected.collegeId
    && current.academicYearId === expected.academicYearId && current.keyword === expected.keyword
}
const teacherFiltersChanged = computed(() => {
  const applied = appliedTeacherFilters.value
  return !applied || applied.collegeId !== props.collegeId || applied.academicYearId !== yearId.value
    || applied.keyword !== teacherKeyword.value.trim()
})
const canReadHistory = computed(() => mode.value === 'STUDENT'
  ? Boolean(yearId.value && previewed.value)
  : Boolean(yearId.value && appliedTeacherFilters.value && !teacherFiltersChanged.value))
const teacherBatchCounts = computed(() => teacherBatchResults.value.reduce((counts, row) => {
  if (row.outcome === 'SUCCESS') counts.success++
  else if (row.outcome === 'UNCHANGED') counts.unchanged++
  else if (row.outcome === 'FAILED') counts.failed++
  return counts
}, { success: 0, unchanged: 0, failed: 0 }))
const studentLabels = { READY: '待开放', EXISTING: '已具备资格，跳过', SUCCESS: '已开放', FAILED: '未成功' }

function message(cause: unknown): string {
  if (cause instanceof ApiError) return cause.message
  return cause instanceof Error ? cause.message : '暂时无法连接服务，请重试。'
}
function setBusy(value: boolean): void { emit('busy', value) }

async function createAcademicYear(): Promise<void> {
  if (yearSaving.value || !yearDraft.value.yearCode.trim() || !yearDraft.value.displayName.trim()
    || !yearDraft.value.changeBasis.trim()) return
  yearSaving.value = true
  yearError.value = ''
  try {
    const year = await personnelManagementService.createAcademicYear({
      collegeId: props.collegeId, yearCode: yearDraft.value.yearCode.trim(),
      displayName: yearDraft.value.displayName.trim(), changeBasis: yearDraft.value.changeBasis.trim(),
    })
    emit('academicYearCreated', year)
    yearId.value = year.id
    result.value = '学年“' + year.displayName + '”已添加到全校学年目录。'
    yearDraft.value = { yearCode: '', displayName: '', changeBasis: '' }
    yearFormOpen.value = false
  } catch (cause) {
    yearError.value = message(cause)
  } finally {
    yearSaving.value = false
  }
}

watch(() => props.years, years => {
  if (!years.some(year => year.id === yearId.value)) yearId.value = years[0]?.id ?? null
}, { immediate: true })

watch([() => props.collegeId, yearId, enrollmentYear], () => {
  studentContextVersion++
  rows.value = []
  previewed.value = false
  error.value = ''
  result.value = ''
  historyVisible.value = false
  historyRows.value = []
})

watch([() => props.collegeId, yearId, mode], ([collegeId, academicYearId, currentMode]) => {
  teacherRequestVersion++
  teacherPeople.value = []
  teacherPeopleTotal.value = 0
  teacherListFailed.value = false
  teacherPageNo.value = 1
  selectedTeachers.value = []
  appliedTeacherFilters.value = null
  teacherEligibilityById.value = {}
  teacherBatchResults.value = []
  batchProgress.value = 0
  batchTotal.value = 0
  error.value = ''
  result.value = ''
  historyVisible.value = false
  historyRows.value = []

  // 切换到导师资格或更换查询学院/学年后立即加载名单，避免空状态被误认为查询结果。
  if (currentMode === 'TEACHER' && collegeId > 0 && academicYearId !== null) {
    void searchTeachers()
  }
})

watch(teacherKeyword, () => {
  historyVisible.value = false
  historyRows.value = []
})

async function previewStudents(): Promise<void> {
  if (busy.value || !yearId.value || !/^\d{4}$/.test(enrollmentYear.value.trim())) return
  const version = studentContextVersion
  const college = props.collegeId
  const year = yearId.value
  const cohort = enrollmentYear.value.trim()
  loading.value = true
  setBusy(true)
  error.value = ''
  result.value = ''
  previewed.value = false
  rows.value = []
  historyVisible.value = false
  historyRows.value = []
  try {
    const existing = await personnelManagementService.eligibility(college, year, 'STUDENT')
    const eligibleIds = new Set(existing.filter(row => row.status === 'ELIGIBLE' && row.validTo === null).map(row => row.personId))
    const candidates = new Map<number, Candidate>()
    let page = 1
    while (true) {
      const response = await personnelManagementService.students(college, page)
      if (version !== studentContextVersion) return
      for (const person of response.items) {
        if (person.collegeId === college && person.enrollmentYearCode === cohort) {
          candidates.set(person.id, {
            ...person,
            status: eligibleIds.has(person.id) ? 'EXISTING' : 'READY',
            failure: '',
          })
        }
      }
      if (page * 20 >= response.total || response.items.length === 0) break
      page++
    }
    if (version !== studentContextVersion) return
    rows.value = [...candidates.values()]
    previewed.value = true
  } catch (cause) {
    if (version === studentContextVersion) error.value = message(cause)
  } finally {
    loading.value = false
    setBusy(false)
  }
}

async function openStudentEligibility(): Promise<void> {
  if (busy.value || !previewed.value || !yearId.value || !basis.value.trim() || !pendingCount.value) return
  const college = props.collegeId
  const year = yearId.value
  const version = studentContextVersion
  const evidence = basis.value.trim()
  saving.value = true
  setBusy(true)
  error.value = ''
  result.value = ''
  historyVisible.value = false
  historyRows.value = []
  let completed = 0
  let failed = 0
  let skipped = 0
  try {
    // 重试前重读当前资格，跳过上次响应丢失但实际已成功的学生。
    const current = await personnelManagementService.eligibility(college, year, 'STUDENT')
    if (version !== studentContextVersion) return
    const eligibleIds = new Set(current.filter(row => row.status === 'ELIGIBLE' && row.validTo === null).map(row => row.personId))
    for (const row of rows.value.filter(item => item.status === 'READY' || item.status === 'FAILED')) {
      if (version !== studentContextVersion) return
      if (eligibleIds.has(row.id)) {
        row.status = 'EXISTING'
        row.failure = ''
        skipped++
        continue
      }
      try {
        await personnelManagementService.setEligibility({
          personType: 'STUDENT', personId: row.id, collegeId: college, academicYearId: year,
          eligibilityStatus: 'ELIGIBLE', evidenceType: 'COHORT_OPEN', evidenceReference: evidence,
          sourceName: `入学年份 ${enrollmentYear.value.trim()} 批量开放`,
        })
        row.status = 'SUCCESS'
        row.failure = ''
        completed++
      } catch (cause) {
        row.status = 'FAILED'
        row.failure = message(cause)
        failed++
      }
    }
    result.value = `本次开放 ${completed} 人，跳过已有资格 ${skipped} 人，失败 ${failed} 人。${failed ? '可重试未成功的学生。' : ''}`
  } catch (cause) {
    error.value = message(cause)
  } finally {
    saving.value = false
    setBusy(false)
  }
}

function captureTeacherFilters(): TeacherFilterSnapshot | null {
  if (yearId.value === null) return null
  return { collegeId: props.collegeId, academicYearId: yearId.value, keyword: teacherKeyword.value.trim() }
}

function currentTeacherEligibility(records: EligibilityRecord[]): Record<number, EligibilityRecord> {
  const current: Record<number, EligibilityRecord> = {}
  records.forEach(record => { if (record.validTo === null) current[record.personId] = record })
  return current
}

async function fetchTeacherPage(filters: TeacherFilterSnapshot, pageNo: number, includeEligibility: boolean): Promise<void> {
  const requestVersion = ++teacherRequestVersion
  loading.value = true
  setBusy(true)
  error.value = ''
  try {
    const peopleRequest = personnelManagementService.teachers(filters.collegeId, pageNo, filters.keyword, 20)
    const eligibilityRequest = includeEligibility
      ? personnelManagementService.eligibility(filters.collegeId, filters.academicYearId, 'TEACHER', filters.keyword).then(
        records => ({ ok: true as const, records }),
        cause => ({ ok: false as const, cause }),
      )
      : Promise.resolve({ ok: true as const, records: null })
    const [page, eligibilityResult] = await Promise.all([peopleRequest, eligibilityRequest])
    if (requestVersion !== teacherRequestVersion || mode.value !== 'TEACHER'
      || !sameTeacherFilters(appliedTeacherFilters.value, filters)) return
    teacherPeople.value = page.items
    teacherPeopleTotal.value = page.total
    teacherListFailed.value = false
    teacherPageNo.value = page.pageNo
    if (eligibilityResult.ok && eligibilityResult.records) {
      teacherEligibilityById.value = currentTeacherEligibility(eligibilityResult.records)
    } else if (!eligibilityResult.ok) {
      teacherEligibilityById.value = {}
      error.value = `导师名单已查询，资格状态读取失败：${message(eligibilityResult.cause)}`
    }
  } catch (cause) {
    if (requestVersion === teacherRequestVersion) {
      error.value = message(cause)
      teacherListFailed.value = true
    }
    throw cause
  } finally {
    loading.value = false
    setBusy(false)
  }
}

async function searchTeachers(): Promise<void> {
  const filters = captureTeacherFilters()
  if (!filters || busy.value) return
  teacherPeople.value = []
  teacherPeopleTotal.value = 0
  teacherListFailed.value = false
  teacherPageNo.value = 1
  selectedTeachers.value = []
  teacherEligibilityById.value = {}
  teacherBatchResults.value = []
  historyVisible.value = false
  historyRows.value = []
  error.value = ''
  result.value = ''
  appliedTeacherFilters.value = filters
  try {
    await fetchTeacherPage(filters, 1, true)
  } catch { /* 错误已在查询区显示。 */ }
}

async function changeTeacherPage(pageNo: number): Promise<void> {
  const filters = appliedTeacherFilters.value
  if (!filters || teacherFiltersChanged.value || busy.value || pageNo < 1 || pageNo > teacherPageCount.value) return
  try { await fetchTeacherPage(filters, pageNo, false) } catch { /* 错误已在查询区显示。 */ }
}

function toggleTeacher(person: PersonRecord): void {
  if (busy.value || teacherFiltersChanged.value) return
  selectedTeachers.value = selectedTeacherIds.value.has(person.id)
    ? selectedTeachers.value.filter(item => item.id !== person.id)
    : [...selectedTeachers.value, person]
}

function toggleCurrentTeacherPage(): void {
  if (busy.value || teacherFiltersChanged.value || !teacherPeople.value.length) return
  const pageIds = new Set(teacherPeople.value.map(person => person.id))
  if (allTeacherPageSelected.value) {
    selectedTeachers.value = selectedTeachers.value.filter(person => !pageIds.has(person.id))
    return
  }
  const selectedById = new Map(selectedTeachers.value.map(person => [person.id, person]))
  teacherPeople.value.forEach(person => selectedById.set(person.id, person))
  selectedTeachers.value = [...selectedById.values()]
}

function isSameEligibility(current: EligibilityRecord | undefined, command: TeacherBatchCommand): boolean {
  return Boolean(current && current.status === command.eligibilityStatus
    && current.evidenceType.trim() === command.evidenceType
    && (current.evidenceReference ?? '').trim() === command.evidenceReference)
}

function updateTeacherBatchResult(person: PersonRecord, outcome: TeacherBatchOutcome, text: string, command: TeacherBatchCommand): void {
  const index = teacherBatchResults.value.findIndex(row => row.person.id === person.id)
  const next = { person, outcome, message: text, command }
  if (index < 0) teacherBatchResults.value.push(next)
  else teacherBatchResults.value[index] = next
}

async function runTeacherBatch(people: PersonRecord[], command: TeacherBatchCommand, retryOnly: boolean): Promise<void> {
  const filters = appliedTeacherFilters.value
  if (!filters || teacherFiltersChanged.value || !people.length || busy.value) return
  if (!command.evidenceType.trim()) {
    error.value = '请填写资格依据类型。'
    return
  }
  const normalized = {
    eligibilityStatus: command.eligibilityStatus,
    evidenceType: command.evidenceType.trim(),
    evidenceReference: command.evidenceReference.trim(),
  }
  const ordered = [...new Map(people.map(person => [person.id, person])).values()]
    .sort((a, b) => a.identifier.localeCompare(b.identifier, 'zh-CN') || a.id - b.id)
  const affectedIds = new Set(ordered.map(person => person.id))
  error.value = ''
  result.value = ''
  historyVisible.value = false
  historyRows.value = []
  saving.value = true
  setBusy(true)
  batchProgress.value = 0
  batchTotal.value = ordered.length
  try {
    let latest: EligibilityRecord[]
    try {
      latest = await personnelManagementService.eligibility(filters.collegeId, filters.academicYearId, 'TEACHER', filters.keyword)
    } catch (cause) {
      error.value = `读取当前资格失败，未开始保存：${message(cause)}`
      return
    }
    teacherEligibilityById.value = currentTeacherEligibility(latest)
    if (!retryOnly) teacherBatchResults.value = []
    ordered.forEach(person => updateTeacherBatchResult(person, 'PROCESSING', '正在处理…', normalized))

    for (const person of ordered) {
      const current = teacherEligibilityById.value[person.id]
      if (isSameEligibility(current, normalized)) {
        updateTeacherBatchResult(person, 'UNCHANGED', '当前资格和依据相同，未重复创建历史版本。', normalized)
      } else {
        try {
          const updated = await personnelManagementService.setEligibility({
            personType: 'TEACHER', personId: person.id, collegeId: filters.collegeId,
            academicYearId: filters.academicYearId, eligibilityStatus: normalized.eligibilityStatus,
            evidenceType: normalized.evidenceType, evidenceReference: normalized.evidenceReference,
          })
          teacherEligibilityById.value = { ...teacherEligibilityById.value, [person.id]: updated }
          updateTeacherBatchResult(person, 'SUCCESS', '资格已保存并记录历史和审计。', normalized)
        } catch (cause) {
          updateTeacherBatchResult(person, 'FAILED', message(cause), normalized)
        }
      }
      batchProgress.value++
    }

    const failed = ordered.filter(person => teacherBatchResults.value.find(row => row.person.id === person.id)?.outcome === 'FAILED')
    selectedTeachers.value = [
      ...selectedTeachers.value.filter(person => !affectedIds.has(person.id)),
      ...failed,
    ]
    result.value = `批量处理完成：成功 ${teacherBatchCounts.value.success} 人，未变化 ${teacherBatchCounts.value.unchanged} 人，失败 ${teacherBatchCounts.value.failed} 人。`
  } finally {
    saving.value = false
    setBusy(false)
  }
}

function saveTeacherQualifications(): Promise<void> {
  return runTeacherBatch(selectedTeachers.value, {
    eligibilityStatus: teacherStatus.value,
    evidenceType: teacherEvidenceType.value,
    evidenceReference: teacherEvidenceReference.value,
  }, false)
}

function retryFailedTeachers(): Promise<void> {
  const failed = teacherBatchResults.value.filter(row => row.outcome === 'FAILED')
  if (!failed.length) return Promise.resolve()
  return runTeacherBatch(failed.map(row => row.person), failed[0].command, true)
}

async function loadEligibilityHistory(): Promise<void> {
  if (busy.value || !canReadHistory.value || !yearId.value) return
  loading.value = true
  setBusy(true)
  error.value = ''
  try {
    const records = mode.value === 'STUDENT'
      ? await personnelManagementService.eligibility(props.collegeId, yearId.value, 'STUDENT', '', true)
      : await personnelManagementService.eligibility(props.collegeId, yearId.value, 'TEACHER', appliedTeacherFilters.value?.keyword ?? '', true)
    if (mode.value === 'STUDENT') {
      const cohortIds = new Set(rows.value.map(row => row.id))
      historyRows.value = records.filter(record => cohortIds.has(record.personId))
    } else {
      historyRows.value = records
    }
    historyVisible.value = true
  } catch (cause) {
    error.value = `读取资格历史失败：${message(cause)}`
  } finally {
    loading.value = false
    setBusy(false)
  }
}
</script>

<template>
  <section class="cohort-eligibility" aria-labelledby="cohort-eligibility-title">
    <p class="scope-label">当前查询学院：<strong>{{ collegeName || '当前授权学院' }}</strong></p>
    <div class="qualification-mode" role="tablist" aria-label="年度资格人员类型">
      <button type="button" role="tab" :aria-selected="mode === 'STUDENT'" :class="{ active: mode === 'STUDENT' }" :disabled="busy" @click="mode = 'STUDENT'">学生资格</button>
      <button type="button" role="tab" :aria-selected="mode === 'TEACHER'" :class="{ active: mode === 'TEACHER' }" :disabled="busy" @click="mode = 'TEACHER'">导师资格</button>
    </div>

    <div class="academic-year-toolbar">
      <label>参与学年<select v-model.number="yearId" :disabled="busy || yearSaving"><option v-for="year in years" :key="year.id" :value="year.id">{{ year.displayName }}</option><option v-if="!years.length" :value="null" disabled>暂无学年，请先新增</option></select></label>
      <button type="button" class="academic-year-toggle" :disabled="yearSaving" @click="yearFormOpen = !yearFormOpen">{{ yearFormOpen ? '收起' : '新增参与学年' }}</button>
    </div>
    <form v-if="yearFormOpen" class="academic-year-create" @submit.prevent="createAcademicYear">
      <p>新增的学年会加入全校共享目录；学年代码必须唯一。</p>
      <label>学年代码<input v-model.trim="yearDraft.yearCode" maxlength="16" placeholder="例如 2027" required :disabled="yearSaving" /></label>
      <label>学年名称<input v-model.trim="yearDraft.displayName" maxlength="64" placeholder="例如 2027 测试学年" required :disabled="yearSaving" /></label>
      <label class="academic-year-reason">新增原因<textarea v-model.trim="yearDraft.changeBasis" maxlength="2000" placeholder="说明新增该学年的原因" required :disabled="yearSaving" /></label>
      <p v-if="yearError" class="academic-year-error" role="alert">{{ yearError }}</p>
      <div class="academic-year-actions"><button type="submit" :disabled="yearSaving">{{ yearSaving ? '正在创建…' : '创建学年' }}</button><button type="button" class="academic-year-cancel" :disabled="yearSaving" @click="yearFormOpen = false">取消</button></div>
    </form>

    <template v-if="mode === 'STUDENT'">
      <h4 id="cohort-eligibility-title">按入学年份统一开放学生资格</h4>
      <p>选择参与学年和入学年份，预览学院学生名单后统一开放资格。</p>
      <div class="cohort-fields">
        <label>入学年份<input v-model="enrollmentYear" inputmode="numeric" maxlength="4" placeholder="例如 2026" :disabled="busy" /></label>
        <label class="cohort-basis">开放依据<textarea v-model="basis" maxlength="4000" placeholder="例如：学院确认的 2026 级已录取并报到学生名单" :disabled="busy" /></label>
      </div>
      <div class="cohort-actions">
        <button type="button" :disabled="busy || !yearId || !/^\d{4}$/.test(enrollmentYear.trim())" @click="previewStudents">{{ loading ? '正在读取名单…' : '预览学生名单' }}</button>
        <button type="button" :disabled="busy || !previewed || !pendingCount || !basis.trim()" @click="openStudentEligibility">{{ saving ? '正在开放资格…' : `统一开放资格（${pendingCount} 人）` }}</button>
      </div>
      <p v-if="error" role="alert">{{ error }}</p><p v-if="result" role="status">{{ result }}</p>
      <template v-if="previewed">
        <p>符合学院和入学年份的学生共 {{ rows.length }} 人，待开放 {{ pendingCount }} 人。已具备资格的学生不会重复生成资格版本。</p>
        <div class="cohort-table"><table><thead><tr><th>姓名</th><th>学号</th><th>专业</th><th>入学年份</th><th>办理状态</th></tr></thead><tbody>
          <tr v-for="row in rows" :key="row.id"><td>{{ row.fullName }}</td><td>{{ row.identifier }}</td><td>{{ row.majorName }}</td><td>{{ row.enrollmentYearCode }}</td><td>{{ studentLabels[row.status] }}<small v-if="row.failure">{{ row.failure }}</small></td></tr>
          <tr v-if="!rows.length"><td colspan="5">没有符合条件的学生，请检查所选学院及学生档案的入学年份。</td></tr>
        </tbody></table></div>
      </template>
      <p class="cohort-note">开放作用于预览名单和所选学年。之后新增的学生需再次预览开放；账号停用及批次已冻结名单的规则仍生效。</p>
    </template>

    <template v-else>
      <h4 id="cohort-eligibility-title">按年度授权导师资格</h4>
      <p>仅查询上方选定学院内的导师。按工号或姓名筛选后，可批量授予或取消资格；每位导师分别校验并记录历史和审计。</p>
      <div class="teacher-filters">
        <label class="teacher-keyword">导师工号或姓名<input v-model="teacherKeyword" placeholder="输入工号或姓名，可留空查询全院导师" :disabled="busy" /></label>
        <button type="button" :disabled="busy || !yearId" @click="searchTeachers">{{ loading ? '查询中…' : '查询导师' }}</button>
      </div>
      <p v-if="appliedTeacherFilters && teacherFiltersChanged" class="teacher-notice" role="status">筛选条件已更改，请重新查询。重新查询会清空已选导师。</p>
      <p v-if="error" role="alert">{{ error }}</p>

      <template v-if="appliedTeacherFilters">
        <div class="teacher-selection-bar">
          <span>本次已选 <strong>{{ selectedTeachers.length }}</strong> 位导师</span>
          <label><input type="checkbox" :checked="allTeacherPageSelected" :disabled="busy || teacherFiltersChanged || !teacherPeople.length" @change="toggleCurrentTeacherPage" />全选当前页</label>
        </div>
        <div class="cohort-table"><table><thead><tr><th class="check-column">选择</th><th>导师</th><th>工号</th><th>资料状态</th><th>当前年度资格</th></tr></thead><tbody>
          <tr v-for="person in teacherPeople" :key="person.id">
            <td class="check-column"><input type="checkbox" :checked="selectedTeacherIds.has(person.id)" :disabled="busy || teacherFiltersChanged" :aria-label="`选择导师${person.fullName}`" @change="toggleTeacher(person)" /></td>
            <td>{{ person.fullName }}</td><td>{{ person.identifier }}</td><td>{{ person.profileReviewStatus ?? '资料未完善' }}</td>
            <td><span v-if="teacherEligibilityById[person.id]" :class="teacherEligibilityById[person.id].status === 'ELIGIBLE' ? 'status eligible' : 'status ineligible'">{{ teacherEligibilityById[person.id].status === 'ELIGIBLE' ? '具备资格' : '不具备资格' }}</span><span v-else class="status unset">未设置</span></td>
          </tr>
          <tr v-if="loading && !teacherPeople.length"><td colspan="5">正在查询导师名单…</td></tr>
          <tr v-else-if="!appliedTeacherFilters"><td colspan="5">正在准备导师查询条件…</td></tr>
          <tr v-else-if="teacherListFailed"><td colspan="5">导师名单加载失败，请处理上方提示后重试。</td></tr>
          <tr v-else-if="!teacherPeople.length"><td colspan="5">当前选定学院（ID {{ appliedTeacherFilters.collegeId }}）范围内没有匹配导师，请核对上方学院选择及导师档案所属学院。</td></tr>
        </tbody></table></div>
        <div class="teacher-pagination"><span>{{ teacherListFailed ? '导师名单加载失败' : `共 ${teacherPeopleTotal} 位导师` }} · 第 {{ teacherPageNo }} / {{ teacherPageCount }} 页</span><div>
          <button type="button" :disabled="busy || teacherFiltersChanged || teacherPageNo <= 1" @click="changeTeacherPage(teacherPageNo - 1)">上一页</button>
          <button type="button" :disabled="busy || teacherFiltersChanged || teacherPageNo >= teacherPageCount" @click="changeTeacherPage(teacherPageNo + 1)">下一页</button>
        </div></div>
        <div class="teacher-command">
          <label>批量资格状态<select v-model="teacherStatus" :disabled="busy"><option value="ELIGIBLE">具备资格</option><option value="INELIGIBLE">不具备资格</option></select></label>
          <label>资格依据类型<input v-model="teacherEvidenceType" maxlength="32" placeholder="例如 ROSTER_IMPORT" :disabled="busy" /></label>
          <label class="teacher-evidence">资格依据说明<textarea v-model="teacherEvidenceReference" maxlength="4000" rows="2" placeholder="审批编号或名单来源说明" :disabled="busy" /></label>
          <div class="teacher-actions">
            <button type="button" :disabled="busy || teacherFiltersChanged || !selectedTeachers.length" @click="saveTeacherQualifications">{{ saving ? `正在逐人保存 ${batchProgress} / ${batchTotal}…` : `批量保存导师资格（${selectedTeachers.length} 人）` }}</button>
            <button v-if="teacherBatchCounts.failed" type="button" class="secondary" :disabled="busy || teacherFiltersChanged" @click="retryFailedTeachers">{{ saving ? '正在重试…' : `重试失败项（${teacherBatchCounts.failed} 人）` }}</button>
            <small v-if="teacherBatchCounts.failed">失败项已保留，可使用上次状态和依据重试。</small>
          </div>
        </div>
        <p v-if="result" role="status">{{ result }}</p>
        <section v-if="teacherBatchResults.length" class="teacher-report" aria-live="polite">
          <div class="teacher-counts"><span class="count success">{{ teacherBatchCounts.success }} 成功</span><span class="count unchanged">{{ teacherBatchCounts.unchanged }} 未变化</span><span class="count failed">{{ teacherBatchCounts.failed }} 失败</span><span v-if="saving">正在处理 {{ batchProgress }} / {{ batchTotal }}</span></div>
          <div class="cohort-table"><table><thead><tr><th>导师</th><th>工号</th><th>结果</th><th>说明</th></tr></thead><tbody>
            <tr v-for="row in teacherBatchResults" :key="row.person.id"><td>{{ row.person.fullName }}</td><td>{{ row.person.identifier }}</td><td>{{ row.outcome === 'SUCCESS' ? '成功' : row.outcome === 'UNCHANGED' ? '未变化' : row.outcome === 'FAILED' ? '失败' : '处理中' }}</td><td>{{ row.message }}</td></tr>
          </tbody></table></div>
        </section>
      </template>
      <p v-else class="cohort-note">查询后可跨页保留选择；批量保存期间筛选条件和提交内容会锁定。</p>
    </template>

    <div class="history-toolbar">
      <button type="button" :disabled="busy || !canReadHistory" @click="loadEligibilityHistory">{{ loading ? '正在读取历史…' : historyVisible ? '刷新资格历史' : '查看资格历史' }}</button>
      <button v-if="historyVisible" type="button" class="history-collapse" :disabled="busy" @click="historyVisible = false">收起</button>
    </div>
    <section v-if="historyVisible" class="history-section" aria-label="年度资格历史记录">
      <div class="cohort-table"><table><thead><tr><th>学年</th><th>人员</th><th>编号</th><th>资格</th><th>依据</th><th>生效区间</th></tr></thead><tbody>
        <tr v-for="record in historyRows" :key="record.id"><td>{{ record.yearCode }}</td><td>{{ record.personName }}</td><td>{{ record.personIdentifier }}</td><td>{{ record.status === 'ELIGIBLE' ? '具备资格' : '不具备资格' }}</td><td>{{ record.evidenceType }}<small v-if="record.evidenceReference">{{ record.evidenceReference }}</small></td><td>{{ record.validFrom ?? '—' }}<br/><small>{{ record.validTo ? `至 ${record.validTo}` : '当前有效' }}</small></td></tr>
        <tr v-if="!historyRows.length"><td colspan="6">当前名单没有资格历史记录。</td></tr>
      </tbody></table></div>
    </section>
  </section>
</template>

<style scoped>
.cohort-eligibility { margin-bottom: 28px; padding: 22px; border: 1px solid #cfe2ec; border-left: 4px solid var(--hnust-blue); border-radius: 8px; background: #f5fafc; }
.cohort-eligibility h4 { margin: 0 0 12px; }.cohort-eligibility p { color: var(--hnust-muted); line-height: 1.7; }
.scope-label { margin: 0 0 11px!important; font-size: 12px; }.scope-label strong { color: var(--hnust-blue-deep); }
.qualification-mode { display: flex; gap: 7px; margin: 0 0 18px; border-bottom: 1px solid #d7e4e9; }
.qualification-mode button { padding: 8px 13px; border: 1px solid transparent; border-radius: 5px 5px 0 0; background: transparent; color: var(--hnust-muted); font: inherit; cursor: pointer; }
.qualification-mode button.active { border-color: #cfe0e6; border-bottom-color: #f5fafc; background: #e8f0f3; color: var(--hnust-blue-deep); font-weight: 700; }
.academic-year-toolbar { display: flex; align-items: end; gap: 12px; margin: 16px 0; }
.academic-year-toolbar label { display: grid; flex: 1; max-width: 460px; gap: 8px; font-weight: 650; }
.academic-year-toolbar select,.academic-year-create input,.academic-year-create textarea { min-width: 0; padding: 11px 12px; border: 1px solid #c9dce6; border-radius: 5px; background: white; font: inherit; }
.academic-year-toggle,.academic-year-actions button { padding: 10px 15px; border: 1px solid var(--hnust-blue); border-radius: 5px; background: var(--hnust-blue); color: white; font: inherit; cursor: pointer; }
.academic-year-create { display: grid; grid-template-columns: 1fr 1fr; gap: 13px; margin: 0 0 18px; padding: 16px; border: 1px solid #cfe2ec; border-radius: 6px; background: #fff; }
.academic-year-create>p:first-child,.academic-year-reason,.academic-year-error,.academic-year-actions { grid-column: 1 / -1; }
.academic-year-create>p:first-child { margin: 0; color: var(--hnust-muted); font-size: 12px; }
.academic-year-create label { display: grid; gap: 7px; font-weight: 650; }
.academic-year-create textarea { min-height: 70px; resize: vertical; }
.academic-year-error { margin: 0; color: #b43d3d!important; }
.academic-year-actions { display: flex; gap: 8px; }
.academic-year-actions .academic-year-cancel { border-color: #c9dce6; background: white; color: var(--hnust-blue-deep); }
.academic-year-actions button:disabled,.academic-year-toggle:disabled { opacity: .5; cursor: default; }
.cohort-fields,.teacher-filters { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }.cohort-fields label,.teacher-filters label,.teacher-command label { display: grid; gap: 8px; font-weight: 650; }
.cohort-basis,.teacher-evidence { grid-column: 1 / -1; }
.cohort-fields input,.cohort-fields select,.cohort-fields textarea,.teacher-filters input,.teacher-filters select,.teacher-command input,.teacher-command select,.teacher-command textarea { min-width: 0; padding: 12px; border: 1px solid #c9dce6; border-radius: 5px; font: inherit; background: white; }
.cohort-actions { display: flex; flex-wrap: wrap; gap: 12px; margin: 18px 0; }
.cohort-actions button,.teacher-filters button,.teacher-actions button,.teacher-pagination button { padding: 10px 16px; color: white; background: var(--hnust-blue); border: 0; border-radius: 5px; font: inherit; cursor: pointer; }
.cohort-actions button:disabled,.teacher-filters button:disabled,.teacher-actions button:disabled,.teacher-pagination button:disabled { opacity: .45; cursor: default; }
.cohort-table { overflow: auto; max-height: 420px; }table { width: 100%; border-collapse: collapse; text-align: left; }td,th { padding: 11px; border-bottom: 1px solid #dce7ee; }small { display: block; color: #b43d3d; }
.cohort-note { font-size: 12px; }.teacher-selection-bar { display: flex; align-items: center; justify-content: space-between; gap: 14px; margin: 12px 0 9px; padding: 10px 12px; border: 1px solid #dce7ea; border-radius: 5px; background: #f7fafb; color: var(--hnust-muted); font-size: 12px; }
.teacher-selection-bar strong { color: var(--hnust-blue-deep); font-size: 14px; }.teacher-selection-bar label { display: flex; align-items: center; gap: 7px; color: var(--hnust-blue-deep); font-weight: 650; }
.check-column { width: 65px; text-align: center; }.check-column input,.teacher-selection-bar input { width: 16px; height: 16px; accent-color: var(--hnust-blue-dark); vertical-align: middle; }
.status { display: inline-flex; padding: 4px 8px; border-radius: 999px; font-size: 11px; font-weight: 700; white-space: nowrap; }.status.eligible { background: #eaf3ed; color: #4f705e; }.status.ineligible { background: #f4ebeb; color: #805959; }.status.unset { background: #f1f3f4; color: #7a878d; }
.teacher-pagination { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 10px 0 14px; color: var(--hnust-muted); font-size: 12px; }.teacher-pagination div { display: flex; gap: 8px; }.teacher-pagination button { padding: 7px 11px; background: transparent; border: 1px solid #c9dce6; color: var(--hnust-blue-deep); }
.teacher-command { display: grid; grid-template-columns: minmax(160px,.7fr) minmax(180px,1fr); gap: 14px; margin-top: 14px; }.teacher-command .teacher-evidence,.teacher-actions { grid-column: 1 / -1; }
.teacher-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 9px; }.teacher-actions button.secondary { color: var(--hnust-blue-deep); border: 1px solid #c9dce6; background: white; }.teacher-actions small { color: var(--hnust-muted); font-size: 11px; }
.teacher-notice { margin: 10px 0; color: #8a6d3b!important; font-size: 12px; }.teacher-report { display: grid; gap: 10px; margin-top: 14px; }.teacher-counts { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; color: var(--hnust-muted); font-size: 12px; }.count { padding: 6px 9px; border-radius: 4px; }.count.success { background: #eaf3ed; color: #4f705e; }.count.unchanged { background: #eef2f4; color: #65747b; }.count.failed { background: #f4ebeb; color: #805959; }
.history-toolbar { display: flex; gap: 9px; margin-top: 16px; padding-top: 12px; border-top: 1px solid #dce7ee; }.history-toolbar button { padding: 7px 11px; border: 1px solid #c9dce6; border-radius: 5px; background: white; color: var(--hnust-blue-deep); font: inherit; cursor: pointer; }.history-toolbar button:disabled { opacity: .5; cursor: default; }.history-section { margin-top: 12px; }
@media(max-width:760px) { .cohort-fields,.teacher-filters,.teacher-command,.academic-year-create { grid-template-columns: 1fr; }.cohort-basis,.teacher-evidence,.teacher-actions,.academic-year-reason,.academic-year-actions { grid-column: 1; }.academic-year-toolbar { align-items: stretch; flex-direction: column; }.academic-year-toolbar label { max-width: none; } }
</style>
