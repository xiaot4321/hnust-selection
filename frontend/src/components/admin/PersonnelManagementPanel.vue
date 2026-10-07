<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ApiError } from '../../api/http'
import {
  personnelManagementService,
  type AcademicYearOption,
  type CollegeOption,
  type EligibilityRecord,
  type ImportResult,
  type MajorRecord,
} from '../../services/personnelManagementService'

/** 父页面只按会话授权控制入口显示；每个 API 仍由服务端复核真实权限和学院归属。 */
defineProps<{ canManage: boolean }>()

type Tab = 'students' | 'teachers' | 'majors' | 'eligibility' | 'imports'
const activeTab = ref<Tab>('students')
const colleges = ref<CollegeOption[]>([])
const collegeOptionsLoaded = ref(false)
const years = ref<AcademicYearOption[]>([])
const collegeId = ref<number | null>(null)
const loading = ref(false)
const saving = ref(false)
const errorMessage = ref('')
const successMessage = ref('')
const students = ref<PersonRecord[]>([])
const teachers = ref<PersonRecord[]>([])
const studentTotal = ref(0)
const teacherTotal = ref(0)
const majors = ref<MajorRecord[]>([])
const eligibilityRows = ref<EligibilityRecord[]>([])
const importResult = ref<ImportResult | null>(null)
const createdPerson = ref<CreatedPerson | null>(null)
const currentFile = ref<File | null>(null)
const studentPageNo = ref(1)
const teacherPageNo = ref(1)
const pageSize = 20
let loadingCount = 0
let collegeDataRequestId = 0
let studentListRequestId = 0
let teacherListRequestId = 0
const peopleType = ref<'STUDENT' | 'TEACHER'>('STUDENT')
const eligibilityYearId = ref<number | null>(null)
const showEligibilityHistory = ref(false)
const importLookupId = ref('')
const studentSearch = ref('')
const teacherSearch = ref('')
const eligibilityIdentifier = ref('')
const foundPerson = ref<PersonRecord | null>(null)

// 人员新建表单只包含账号与学校基础档案字段；角色、初始密码和分类版本由后端生成。
const studentForm = reactive({
  studentNo: '', fullName: '', majorCode: '', degreeType: 'ACADEMIC_MASTER',
  enrollmentYearCode: '', classificationBasis: '', classificationReason: '',
})
const teacherForm = reactive({ employeeNo: '', fullName: '' })
const majorForm = reactive({ majorCode: '', name: '', validFrom: '', validTo: '', changeBasis: '' })
const editingMajorId = ref<number | null>(null)
const eligibilityForm = reactive({ status: 'ELIGIBLE', evidenceType: 'ROSTER_IMPORT', evidenceReference: '' })
const importForm = reactive({ personType: 'STUDENT' as 'STUDENT' | 'TEACHER' })

const studentTotalPages = computed(() => Math.max(1, Math.ceil(studentTotal.value / pageSize)))
const teacherTotalPages = computed(() => Math.max(1, Math.ceil(teacherTotal.value / pageSize)))

/** 共享加载状态使用计数，避免并发查询中较早结束的请求提前关闭加载提示。 */
function beginLoading(): () => void {
  loadingCount += 1
  loading.value = true
  return () => {
    loadingCount = Math.max(0, loadingCount - 1)
    loading.value = loadingCount > 0
  }
}

function friendlyError(error: unknown): string {
  if (!(error instanceof ApiError)) return error instanceof Error ? error.message : '暂时无法连接服务，请稍后重试。'
  const messages: Record<string, string> = {
    SCOPE_FORBIDDEN: '当前账号没有这个学院的人员管理授权。',
    STATE_CONFLICT: '记录已经变化或标识重复，请刷新后核对。',
    IDEMPOTENCY_KEY_REUSED: '这次请求的操作标识已用于其他内容，请重新提交。',
    REQUEST_IN_PROGRESS: '同一操作仍在处理，请稍后查看结果。',
    INVALID_ARGUMENT: error.message,
  }
  return messages[error.code] ?? error.message
}

async function loadOptions(): Promise<void> {
  const finishLoading = beginLoading()
  errorMessage.value = ''
  try {
    colleges.value = await personnelManagementService.colleges()
    collegeOptionsLoaded.value = true
    if (collegeId.value === null && colleges.value.length) collegeId.value = colleges.value[0].id
  } catch (error) {
    errorMessage.value = friendlyError(error)
  } finally {
    finishLoading()
  }
}

async function loadCollegeData(): Promise<void> {
  if (collegeId.value === null) return
  const requestedCollegeId = collegeId.value
  const requestId = ++collegeDataRequestId
  // 完整刷新会分别使两类较早的名单请求失效；之后新发起的筛选仍优先于这次刷新。
  const studentRequestId = ++studentListRequestId
  const teacherRequestId = ++teacherListRequestId
  const finishLoading = beginLoading()
  errorMessage.value = ''
  try {
    const [yearOptions, majorOptions, studentPage, teacherPage] = await Promise.all([
      personnelManagementService.academicYears(requestedCollegeId),
      personnelManagementService.majors(requestedCollegeId),
      personnelManagementService.students(requestedCollegeId, studentPageNo.value, studentSearch.value),
      personnelManagementService.teachers(requestedCollegeId, teacherPageNo.value, teacherSearch.value),
    ])
    if (requestId !== collegeDataRequestId || collegeId.value !== requestedCollegeId) return
    years.value = yearOptions
    if (!yearOptions.some((year) => year.id === eligibilityYearId.value)) {
      eligibilityYearId.value = yearOptions[0]?.id ?? null
    }
    majors.value = majorOptions
    // 学生或导师的新查询若在整页刷新期间启动，只更新尚未被新查询接管的名单。
    if (studentRequestId === studentListRequestId) {
      students.value = studentPage.items
      studentTotal.value = studentPage.total
      studentPageNo.value = studentPage.pageNo
    }
    if (teacherRequestId === teacherListRequestId) {
      teachers.value = teacherPage.items
      teacherTotal.value = teacherPage.total
      teacherPageNo.value = teacherPage.pageNo
    }
  } catch (error) {
    if (requestId === collegeDataRequestId) errorMessage.value = friendlyError(error)
  } finally {
    finishLoading()
  }
}

watch(collegeId, (newCollegeId, previousCollegeId) => {
  if (newCollegeId === previousCollegeId) return
  studentPageNo.value = 1
  teacherPageNo.value = 1
  studentTotal.value = 0
  teacherTotal.value = 0
  students.value = []
  teachers.value = []
  years.value = []
  majors.value = []
  eligibilityRows.value = []
  foundPerson.value = null
  editingMajorId.value = null
  Object.assign(majorForm, { majorCode: '', name: '', validFrom: '', validTo: '', changeBasis: '' })
  void loadCollegeData()
})
onMounted(() => { void loadOptions() })

function resetMessages(): void {
  errorMessage.value = ''
  successMessage.value = ''
  createdPerson.value = null
  importResult.value = null
}

async function createStudent(): Promise<void> {
  if (collegeId.value === null || saving.value) return
  resetMessages(); saving.value = true
  try {
    createdPerson.value = await personnelManagementService.createStudent({
      ...studentForm, loginIdentifier: studentForm.studentNo, collegeId: collegeId.value,
    })
    successMessage.value = '学生账号和初始档案已创建。初始密码为学号末尾六位（学号不足六位时使用完整学号）；请告知学生在首次登录后立即设置正式密码。'
    Object.assign(studentForm, { studentNo: '', fullName: '', majorCode: '', degreeType: 'ACADEMIC_MASTER', enrollmentYearCode: '', classificationBasis: '', classificationReason: '' })
    await loadCollegeData()
  } catch (error) { errorMessage.value = friendlyError(error) }
  finally { saving.value = false }
}

async function createTeacher(): Promise<void> {
  if (collegeId.value === null || saving.value) return
  resetMessages(); saving.value = true
  try {
    createdPerson.value = await personnelManagementService.createTeacher({
      ...teacherForm, loginIdentifier: teacherForm.employeeNo, collegeId: collegeId.value,
    })
    successMessage.value = '导师账号和空白资料档案已创建。初始密码为工号末尾六位（工号不足六位时使用完整工号）；导师首次登录后须设置正式密码，公开资料完善并审核后才进入目录。'
    Object.assign(teacherForm, { employeeNo: '', fullName: '' })
    await loadCollegeData()
  } catch (error) { errorMessage.value = friendlyError(error) }
  finally { saving.value = false }
}

function editMajor(major: MajorRecord): void {
  editingMajorId.value = major.id
  Object.assign(majorForm, {
    majorCode: major.majorCode, name: major.name, validFrom: major.validFrom ?? '',
    validTo: major.validTo ?? '', changeBasis: '',
  })
}

function clearMajorForm(): void {
  editingMajorId.value = null
  Object.assign(majorForm, { majorCode: '', name: '', validFrom: '', validTo: '', changeBasis: '' })
}

async function saveMajor(): Promise<void> {
  if (collegeId.value === null || saving.value) return
  resetMessages(); saving.value = true
  const payload = { ...majorForm, collegeId: collegeId.value, active: true }
  try {
    if (editingMajorId.value === null) await personnelManagementService.createMajor(payload)
    else await personnelManagementService.updateMajor(editingMajorId.value, payload)
    successMessage.value = editingMajorId.value === null ? '专业目录项已新增。' : '专业目录项已更新。'
    clearMajorForm()
    majors.value = await personnelManagementService.majors(collegeId.value)
  } catch (error) { errorMessage.value = friendlyError(error) }
  finally { saving.value = false }
}

async function toggleMajor(major: MajorRecord): Promise<void> {
  if (collegeId.value === null || saving.value) return
  resetMessages(); saving.value = true
  try {
    await personnelManagementService.updateMajor(major.id, {
      name: major.name, active: !major.active, validFrom: major.validFrom,
      validTo: major.validTo, changeBasis: major.active ? '管理员停用专业目录项' : '管理员重新启用专业目录项',
    })
    majors.value = await personnelManagementService.majors(collegeId.value)
    successMessage.value = major.active ? '专业已停用；历史人员档案保留原专业引用。' : '专业已重新启用。'
  } catch (error) { errorMessage.value = friendlyError(error) }
  finally { saving.value = false }
}

async function lookupEligibilityPerson(): Promise<void> {
  if (collegeId.value === null || !eligibilityIdentifier.value.trim()) return
  resetMessages(); foundPerson.value = null
  const finishLoading = beginLoading()
  try {
    const page = peopleType.value === 'STUDENT'
      ? await personnelManagementService.students(collegeId.value, 1, eligibilityIdentifier.value)
      : await personnelManagementService.teachers(collegeId.value, 1, eligibilityIdentifier.value)
    foundPerson.value = page.items[0] ?? null
    if (!foundPerson.value) errorMessage.value = '没有在当前授权学院找到这个学号或工号。'
  } catch (error) { errorMessage.value = friendlyError(error) }
  finally { finishLoading() }
}

async function loadEligibility(): Promise<void> {
  if (collegeId.value === null || eligibilityYearId.value === null) return
  const finishLoading = beginLoading(); errorMessage.value = ''
  try {
    eligibilityRows.value = await personnelManagementService.eligibility(
      collegeId.value, eligibilityYearId.value, peopleType.value, eligibilityIdentifier.value, showEligibilityHistory.value,
    )
  } catch (error) { errorMessage.value = friendlyError(error) }
  finally { finishLoading() }
}

async function saveEligibility(): Promise<void> {
  if (collegeId.value === null || eligibilityYearId.value === null || !foundPerson.value || saving.value) return
  resetMessages(); saving.value = true
  try {
    await personnelManagementService.setEligibility({
      personType: peopleType.value, personId: foundPerson.value.id, collegeId: collegeId.value,
      academicYearId: eligibilityYearId.value, eligibilityStatus: eligibilityForm.status,
      evidenceType: eligibilityForm.evidenceType, evidenceReference: eligibilityForm.evidenceReference,
    })
    successMessage.value = '年度资格已保存为新版本，历史记录仍可查询。'
    foundPerson.value = null; eligibilityForm.evidenceReference = ''
    await loadEligibility()
  } catch (error) { errorMessage.value = friendlyError(error) }
  finally { saving.value = false }
}

async function downloadTemplate(): Promise<void> {
  if (collegeId.value === null) return
  resetMessages()
  try { await personnelManagementService.downloadTemplate(collegeId.value, importForm.personType) }
  catch (error) { errorMessage.value = friendlyError(error) }
}

function onFileChange(event: Event): void {
  const input = event.target as HTMLInputElement
  currentFile.value = input.files?.[0] ?? null
  importResult.value = null
}

async function uploadRoster(): Promise<void> {
  if (collegeId.value === null || eligibilityYearId.value === null || !currentFile.value || saving.value) return
  resetMessages(); saving.value = true
  try {
    importResult.value = await personnelManagementService.importPersonnel(
      collegeId.value, eligibilityYearId.value, importForm.personType, currentFile.value,
    )
    successMessage.value = `导入任务 ${importResult.value.importId} 已处理：成功 ${importResult.value.acceptedCount} 行，失败 ${importResult.value.rejectedCount} 行。`
    currentFile.value = null
    await loadCollegeData()
  } catch (error) { errorMessage.value = friendlyError(error) }
  finally { saving.value = false }
}

async function lookupImportRows(): Promise<void> {
  const id = Number(importLookupId.value)
  if (!Number.isSafeInteger(id) || id <= 0) { errorMessage.value = '请输入有效的导入编号。'; return }
  resetMessages(); const finishLoading = beginLoading()
  try {
    const rows = await personnelManagementService.importRows(id)
    importResult.value = {
      importId: id, personType: importForm.personType, status: 'HISTORY',
      acceptedCount: rows.filter((row) => row.status === 'CREATED').length,
      rejectedCount: rows.filter((row) => row.status !== 'CREATED').length,
      rows,
    }
    successMessage.value = '已读取导入历史。历史查询不包含已展示过的一次性临时凭证。'
  } catch (error) { errorMessage.value = friendlyError(error) }
  finally { finishLoading() }
}

async function searchPeople(): Promise<void> {
  if (collegeId.value === null) return
  const requestedCollegeId = collegeId.value
  const isStudentList = activeTab.value === 'students'
  const requestId = isStudentList ? ++studentListRequestId : ++teacherListRequestId
  const requestedPageNo = 1
  const identifier = isStudentList ? studentSearch.value : teacherSearch.value
  const finishLoading = beginLoading()
  errorMessage.value = ''
  try {
    const result = isStudentList
      ? await personnelManagementService.students(requestedCollegeId, requestedPageNo, identifier)
      : await personnelManagementService.teachers(requestedCollegeId, requestedPageNo, identifier)
    if (collegeId.value !== requestedCollegeId
      || requestId !== (isStudentList ? studentListRequestId : teacherListRequestId)) return
    if (isStudentList) {
      students.value = result.items
      studentTotal.value = result.total
      studentPageNo.value = result.pageNo
    } else {
      teachers.value = result.items
      teacherTotal.value = result.total
      teacherPageNo.value = result.pageNo
    }
  } catch (error) {
    if (requestId === (isStudentList ? studentListRequestId : teacherListRequestId)
      && activeTab.value === (isStudentList ? 'students' : 'teachers')) errorMessage.value = friendlyError(error)
  } finally { finishLoading() }
}

async function changePeoplePage(pageNo: number): Promise<void> {
  const isStudentList = activeTab.value === 'students'
  const totalPages = isStudentList ? studentTotalPages.value : teacherTotalPages.value
  if (collegeId.value === null || loading.value || pageNo < 1 || pageNo > totalPages) return
  const requestedCollegeId = collegeId.value
  const identifier = isStudentList ? studentSearch.value : teacherSearch.value
  const requestId = isStudentList ? ++studentListRequestId : ++teacherListRequestId
  const finishLoading = beginLoading()
  errorMessage.value = ''
  try {
    const result = isStudentList
      ? await personnelManagementService.students(requestedCollegeId, pageNo, identifier)
      : await personnelManagementService.teachers(requestedCollegeId, pageNo, identifier)
    if (collegeId.value !== requestedCollegeId
      || requestId !== (isStudentList ? studentListRequestId : teacherListRequestId)) return
    if (isStudentList) {
      students.value = result.items
      studentTotal.value = result.total
      studentPageNo.value = result.pageNo
    } else {
      teachers.value = result.items
      teacherTotal.value = result.total
      teacherPageNo.value = result.pageNo
    }
  } catch (error) {
    if (requestId === (isStudentList ? studentListRequestId : teacherListRequestId)
      && activeTab.value === (isStudentList ? 'students' : 'teachers')) errorMessage.value = friendlyError(error)
  } finally { finishLoading() }
}
</script>

<template>
  <section id="personnel-management" class="personnel-panel" aria-labelledby="personnel-title">
    <header class="personnel-heading">
      <div>
        <p class="personnel-kicker">人员与资格</p>
        <h3 id="personnel-title">人员档案管理</h3>
        <p class="personnel-intro">创建师生账号、维护学院专业目录、记录年度资格，并用固定模板导入名单。</p>
      </div>
      <label class="college-picker">
        <span>授权学院</span>
        <select v-model.number="collegeId" :disabled="loading || colleges.length < 2" aria-label="选择授权学院">
          <option v-for="college in colleges" :key="college.id" :value="college.id">{{ college.name }}</option>
        </select>
      </label>
    </header>

    <p v-if="errorMessage" class="personnel-message personnel-error" role="alert">{{ errorMessage }}</p>
    <p v-if="successMessage" class="personnel-message personnel-success" role="status">{{ successMessage }}</p>
    <p v-if="collegeOptionsLoaded && !colleges.length && !loading" class="personnel-empty">没有查到可管理学院。普通管理员请联系总管理员分配 COLLEGE_ADMIN 授权；总管理员可检查学院基础数据是否已建立。</p>
    <button v-if="!colleges.length && errorMessage" class="personnel-secondary" type="button" :disabled="loading" @click="loadOptions">重试加载学院</button>

    <template v-if="collegeId !== null">
      <nav class="personnel-tabs" aria-label="人员管理功能">
        <button v-for="tab in [
          { id: 'students', label: '学生账号' }, { id: 'teachers', label: '导师账号' },
          { id: 'majors', label: '专业目录' }, { id: 'eligibility', label: '年度资格' }, { id: 'imports', label: '名单导入' },
        ]" :key="tab.id" type="button" :aria-current="activeTab === tab.id ? 'page' : undefined"
          :class="{ 'personnel-tab-active': activeTab === tab.id }" @click="activeTab = tab.id as Tab; resetMessages()">
          {{ tab.label }}
        </button>
      </nav>

      <div v-if="activeTab === 'students'" class="personnel-section">
        <h4>创建学生账号</h4>
        <p class="personnel-help">学号同时作为登录标识。初始密码取学号末尾六位（不足六位时使用完整学号），不设到期时间但仅能成功使用一次；学生首次登录后须设置正式密码。</p>
        <form class="personnel-form" @submit.prevent="createStudent">
          <label>学号<input v-model="studentForm.studentNo" maxlength="64" required /></label>
          <label>姓名<input v-model="studentForm.fullName" maxlength="128" required /></label>
          <label>专业目录项<select v-model="studentForm.majorCode" required><option value="" disabled>选择专业</option><option v-for="major in majors.filter((item) => item.active)" :key="major.id" :value="major.majorCode">{{ major.majorCode }} · {{ major.name }}</option></select></label>
          <label>学位类型<select v-model="studentForm.degreeType"><option value="ACADEMIC_MASTER">学硕</option><option value="PROFESSIONAL_MASTER">专硕</option></select></label>
          <label>入学年份代码<input v-model="studentForm.enrollmentYearCode" maxlength="16" placeholder="例如 2026" required /></label>
          <label>分类依据<input v-model="studentForm.classificationBasis" maxlength="2000" placeholder="学校名单、录取信息等" required /></label>
          <label class="wide-field">分类变更说明<textarea v-model="studentForm.classificationReason" rows="2" placeholder="说明本次初始专业和学位分类的依据" required /></label>
          <button class="personnel-primary" type="submit" :disabled="saving">{{ saving ? '正在创建…' : '创建学生账号' }}</button>
        </form>
        <CredentialNotice v-if="createdPerson?.personType === 'STUDENT'" :person="createdPerson" />
        <div class="personnel-list-heading"><h4>学生名单</h4><form @submit.prevent="searchPeople"><input v-model="studentSearch" placeholder="按学号精确查询"/><button class="personnel-secondary" type="submit">查询</button></form></div>
        <PersonTable :items="students" :kind="'STUDENT'" :loading="loading" />
        <div v-if="studentTotal > 0" class="personnel-pagination" aria-label="学生名单分页">
          <span>共 {{ studentTotal }} 人 · 第 {{ studentPageNo }} / {{ studentTotalPages }} 页</span>
          <div><button class="personnel-secondary" type="button" :disabled="loading || studentPageNo <= 1" @click="changePeoplePage(studentPageNo - 1)">上一页</button><button class="personnel-secondary" type="button" :disabled="loading || studentPageNo >= studentTotalPages" @click="changePeoplePage(studentPageNo + 1)">下一页</button></div>
        </div>
      </div>

      <div v-else-if="activeTab === 'teachers'" class="personnel-section">
        <h4>创建导师账号</h4>
        <p class="personnel-help">工号同时作为登录标识。初始密码取工号末尾六位（不足六位时使用完整工号），不设到期时间但仅能成功使用一次；新导师会得到空白资料档案，公开资料由导师完善并提交审核。</p>
        <form class="personnel-form" @submit.prevent="createTeacher">
          <label>工号<input v-model="teacherForm.employeeNo" maxlength="64" required /></label>
          <label>姓名<input v-model="teacherForm.fullName" maxlength="128" required /></label>
          <button class="personnel-primary" type="submit" :disabled="saving">{{ saving ? '正在创建…' : '创建导师账号' }}</button>
        </form>
        <CredentialNotice v-if="createdPerson?.personType === 'TEACHER'" :person="createdPerson" />
        <div class="personnel-list-heading"><h4>导师名单</h4><form @submit.prevent="searchPeople"><input v-model="teacherSearch" placeholder="按工号精确查询"/><button class="personnel-secondary" type="submit">查询</button></form></div>
        <PersonTable :items="teachers" :kind="'TEACHER'" :loading="loading" />
        <div v-if="teacherTotal > 0" class="personnel-pagination" aria-label="导师名单分页">
          <span>共 {{ teacherTotal }} 人 · 第 {{ teacherPageNo }} / {{ teacherTotalPages }} 页</span>
          <div><button class="personnel-secondary" type="button" :disabled="loading || teacherPageNo <= 1" @click="changePeoplePage(teacherPageNo - 1)">上一页</button><button class="personnel-secondary" type="button" :disabled="loading || teacherPageNo >= teacherTotalPages" @click="changePeoplePage(teacherPageNo + 1)">下一页</button></div>
        </div>
      </div>

      <div v-else-if="activeTab === 'majors'" class="personnel-section">
        <h4>{{ editingMajorId === null ? '新增专业目录项' : '维护专业目录项' }}</h4>
        <form class="personnel-form" @submit.prevent="saveMajor">
          <label>专业代码<input v-model="majorForm.majorCode" maxlength="32" :disabled="editingMajorId !== null" required /></label>
          <label>专业名称<input v-model="majorForm.name" maxlength="128" required /></label>
          <label>生效日期<input v-model="majorForm.validFrom" type="date" /></label>
          <label>失效日期<input v-model="majorForm.validTo" type="date" /></label>
          <label class="wide-field">维护依据<textarea v-model="majorForm.changeBasis" rows="2" required /></label>
          <div class="personnel-form-actions"><button class="personnel-primary" type="submit" :disabled="saving">{{ saving ? '正在保存…' : '保存专业目录' }}</button><button v-if="editingMajorId !== null" class="personnel-secondary" type="button" @click="clearMajorForm">取消编辑</button></div>
        </form>
        <div class="personnel-table-wrap"><table><thead><tr><th>专业代码</th><th>专业名称</th><th>有效期</th><th>状态</th><th>操作</th></tr></thead><tbody>
          <tr v-for="major in majors" :key="major.id"><td>{{ major.majorCode }}</td><td>{{ major.name }}</td><td>{{ major.validFrom ?? '未设置' }} 至 {{ major.validTo ?? '未设置' }}</td><td>{{ major.active ? '启用' : '停用' }}</td><td class="table-actions"><button class="personnel-link" type="button" @click="editMajor(major)">编辑</button><button class="personnel-link" type="button" @click="toggleMajor(major)">{{ major.active ? '停用' : '启用' }}</button></td></tr>
          <tr v-if="!majors.length"><td colspan="5" class="table-empty">尚无专业目录项，请先新增。</td></tr>
        </tbody></table></div>
      </div>

      <div v-else-if="activeTab === 'eligibility'" class="personnel-section">
        <h4>维护年度资格</h4>
        <p class="personnel-help">每人每学年仅保留一条当前状态。再次修改会新增历史版本，原记录会带上失效时间。</p>
        <div class="personnel-form">
          <label>学年<select v-model.number="eligibilityYearId"><option v-for="year in years" :key="year.id" :value="year.id">{{ year.displayName }}</option></select></label>
          <label>人员类型<select v-model="peopleType"><option value="STUDENT">学生</option><option value="TEACHER">导师</option></select></label>
          <label>学号或工号<input v-model="eligibilityIdentifier" placeholder="输入完整编号" /></label>
          <button class="personnel-secondary lookup-button" type="button" :disabled="loading" @click="lookupEligibilityPerson">{{ loading ? '查询中…' : '查找人员' }}</button>
          <label>资格状态<select v-model="eligibilityForm.status"><option value="ELIGIBLE">具备资格</option><option value="INELIGIBLE">不具备资格</option></select></label>
          <label>资格依据类型<input v-model="eligibilityForm.evidenceType" maxlength="32" placeholder="例如 ROSTER_IMPORT" required /></label>
          <label class="wide-field">资格依据说明<textarea v-model="eligibilityForm.evidenceReference" rows="2" placeholder="审批编号或名单来源说明" /></label>
        </div>
        <div v-if="foundPerson" class="found-person">
          <span>已找到 {{ foundPerson.fullName }}（{{ foundPerson.identifier }}）</span>
          <button class="personnel-primary" type="button" :disabled="saving || eligibilityYearId === null" @click="saveEligibility">{{ saving ? '正在保存…' : '保存资格版本' }}</button>
        </div>
        <div class="eligibility-toolbar"><label class="history-toggle"><input v-model="showEligibilityHistory" type="checkbox" @change="loadEligibility" />显示历史版本</label><button class="personnel-secondary" type="button" :disabled="loading || eligibilityYearId === null" @click="loadEligibility">查询资格记录</button></div>
        <div class="personnel-table-wrap"><table><thead><tr><th>学年</th><th>人员</th><th>编号</th><th>资格</th><th>依据</th><th>生效区间</th></tr></thead><tbody>
          <tr v-for="row in eligibilityRows" :key="row.id"><td>{{ row.yearCode }}</td><td>{{ row.personName }} · {{ row.personType === 'STUDENT' ? '学生' : '导师' }}</td><td>{{ row.personIdentifier }}</td><td>{{ row.status === 'ELIGIBLE' ? '具备资格' : '不具备资格' }}</td><td>{{ row.evidenceType }}<small v-if="row.evidenceReference">{{ row.evidenceReference }}</small></td><td>{{ row.validFrom ?? '—' }}<br/><small>{{ row.validTo ? `至 ${row.validTo}` : '当前有效' }}</small></td></tr>
          <tr v-if="!eligibilityRows.length"><td colspan="6" class="table-empty">当前筛选条件下没有资格记录。</td></tr>
        </tbody></table></div>
      </div>

      <div v-else class="personnel-section">
        <h4>导入学生或导师名单</h4>
        <p class="personnel-help">下载固定 CSV 模板后，可用表格软件编辑并保存为 CSV 或 XLSX。登录标识列须与学号/工号相同；新建账号初始密码取对应编号末尾六位（不足六位时使用完整编号），最多 2000 行，单文件不超过 10 MB。</p>
        <div class="personnel-form import-form">
          <label>人员类型<select v-model="importForm.personType"><option value="STUDENT">学生</option><option value="TEACHER">导师</option></select></label>
          <label>所属学年<select v-model.number="eligibilityYearId"><option v-for="year in years" :key="year.id" :value="year.id">{{ year.displayName }}</option></select></label>
          <button class="personnel-secondary" type="button" @click="downloadTemplate">下载 CSV 模板</button>
          <label class="wide-field">选择名单文件<input type="file" accept=".csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" @change="onFileChange" /></label>
          <button class="personnel-primary" type="button" :disabled="saving || !currentFile || eligibilityYearId === null" @click="uploadRoster">{{ saving ? '正在逐行处理…' : '上传并导入' }}</button>
        </div>
        <div class="import-lookup"><label>查看既有导入任务<input v-model="importLookupId" inputmode="numeric" placeholder="输入导入编号" /></label><button class="personnel-secondary" type="button" :disabled="loading" @click="lookupImportRows">查询行结果</button></div>
        <div v-if="importResult" class="import-summary" role="status">
          <strong>导入任务 {{ importResult.importId }} · {{ importResult.status }}</strong>
          <span>成功 {{ importResult.acceptedCount }} 行，失败 {{ importResult.rejectedCount }} 行</span>
        </div>
        <div v-if="importResult?.rows?.length" class="personnel-table-wrap"><table><thead><tr><th>表格行号</th><th>学号/工号</th><th>结果</th><th>说明</th><th>{{ importResult.personType === 'STUDENT' ? '初始密码（学号末尾六位）' : '初始密码（工号末尾六位）' }}</th></tr></thead><tbody>
          <tr v-for="row in importResult.rows" :key="row.rowNumber"><td>{{ row.rowNumber }}</td><td>{{ row.personIdentifier }}</td><td>{{ row.status === 'CREATED' ? '创建成功' : '未创建' }}</td><td>{{ row.errorMessage ?? '账号与资格已创建' }}</td><td class="credential-cell">{{ row.temporaryCredential ?? '历史查询不再显示' }}</td></tr>
        </tbody></table></div>
        <p v-if="importResult?.rows?.some((row) => row.temporaryCredential)" class="credential-warning">初始密码仅在本次导入响应中显示一次，不设到期时间但仅能成功使用一次。请通过受控渠道交付；关闭或刷新此页后，系统不会再次提供明文，并提醒本人首次登录后设置正式密码。</p>
      </div>
    </template>
  </section>
</template>

<!-- 子组件把初始密码集中提示，明确说明其来源与首次展示限制，避免用户误以为之后还能查询明文。 -->
<script lang="ts">
import { defineComponent, h } from 'vue'
import type { CreatedPerson, PersonRecord } from '../../services/personnelManagementService'

const CredentialNotice = defineComponent({
  props: { person: { type: Object as () => CreatedPerson, required: true } },
  setup(props) {
    return () => h('aside', { class: 'credential-notice', role: 'status' }, [
      h('strong', props.person.personType === 'STUDENT' ? '学生初始密码（学号末尾六位）' : '导师初始密码（工号末尾六位）'),
      h('code', props.person.credential.temporaryCredential ?? '此请求已处理；初始密码不会再次展示。'),
      h('span', `登录标识：${props.person.loginIdentifier} · 不设到期时间 · 仅能成功使用一次`),
      h('span', '编号不足六位时，初始密码使用完整编号；首次登录后须立即设置正式密码。'),
    ])
  },
})

const PersonTable = defineComponent({
  props: {
    items: { type: Array as () => PersonRecord[], required: true },
    kind: { type: String, required: true },
    loading: { type: Boolean, default: false },
  },
  setup(props) {
    return () => h('div', { class: 'personnel-table-wrap' }, [
      h('table', [
        h('thead', [h('tr', [h('th', props.kind === 'STUDENT' ? '学号' : '工号'), h('th', '姓名'), h('th', '登录标识'), ...(props.kind === 'STUDENT' ? [h('th', '专业与学位')] : [h('th', '资料状态')])])]),
        h('tbody', props.items.length ? props.items.map((person) => h('tr', { key: person.id }, [
          h('td', person.identifier), h('td', person.fullName), h('td', person.loginIdentifier),
          h('td', props.kind === 'STUDENT' ? `${person.majorName ?? ''} · ${person.degreeType === 'ACADEMIC_MASTER' ? '学硕' : '专硕'}` : person.profileReviewStatus ?? '未完善'),
        ])) : [h('tr', [h('td', { colspan: 4, class: 'table-empty' }, props.loading ? '正在读取…' : '暂时没有符合条件的人员记录。')])]),
      ]),
    ])
  },
})

export default { components: { CredentialNotice, PersonTable } }
</script>

<style scoped>
.personnel-panel { margin-top: 0; padding: 0 0 4px; color: var(--hnust-ink); }
.personnel-heading { display:flex; justify-content:space-between; align-items:flex-end; gap:24px; padding-bottom:14px; border-bottom:1px solid var(--hnust-line); }
.personnel-kicker { display:inline-flex; align-items:center; gap:7px; margin:0 0 7px; padding:4px 8px; border:1px solid #cfe0e6; border-radius:999px; background:#eaf2f5; color:var(--hnust-blue-dark); font-size:10px; font-weight:700; }
.personnel-kicker::before { width:5px; height:5px; border-radius:50%; background:var(--hnust-blue); content:""; }
.personnel-heading h3 { margin:0; color:var(--hnust-blue-deep); font-size:21px; }
.personnel-intro,.personnel-help { margin:9px 0 0; color:var(--hnust-muted); font-size:12px; line-height:1.8; }
.college-picker { display:grid; min-width:210px; gap:6px; color:var(--hnust-muted); font-size:11px; }
.college-picker select,.personnel-panel input,.personnel-panel select,.personnel-panel textarea { min-width:0; padding:10px 11px; border:1px solid #d3dfe3; border-radius:4px; background:#fff; color:var(--hnust-ink); }
.college-picker select:focus,.personnel-panel input:focus,.personnel-panel select:focus,.personnel-panel textarea:focus { border-color:var(--hnust-blue); outline:2px solid rgba(82,120,138,.14); outline-offset:1px; }
.personnel-tabs { display:flex; gap:5px; overflow-x:auto; margin:18px 0 20px; padding-bottom:8px; border-bottom:1px solid var(--hnust-line); }
.personnel-tabs button { flex:0 0 auto; padding:8px 12px; border:1px solid transparent; border-radius:4px; background:transparent; color:var(--hnust-muted); cursor:pointer; font-size:12px; transition:background-color 140ms ease,color 140ms ease,border-color 140ms ease; }
.personnel-tabs button:hover { background:#edf3f5; color:var(--hnust-blue-deep); }
.personnel-tabs button.personnel-tab-active { border-color:#cfe0e6; background:#e8f0f3; color:var(--hnust-blue-deep); font-weight:700; }
.personnel-section h4 { display:flex; align-items:center; gap:8px; margin:0; color:var(--hnust-blue-deep); font-size:15px; }
.personnel-section h4::before { width:4px; height:16px; border-radius:2px; background:var(--hnust-blue); content:""; }
.personnel-section > .personnel-help { margin-bottom:17px; }
.personnel-form { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:13px 16px; align-items:end; margin:16px 0 22px; padding:15px; border:1px solid #dce7ea; border-radius:5px; background:#f7fafb; }
.personnel-form label,.import-lookup label { display:grid; gap:6px; color:#536a73; font-size:11px; font-weight:650; }
.personnel-form .wide-field { grid-column:1 / -1; }
.personnel-form textarea { resize:vertical; }
.personnel-primary,.personnel-secondary { min-height:38px; padding:0 14px; border:1px solid var(--hnust-blue-dark); border-radius:4px; background:var(--hnust-blue-dark); color:#fff; cursor:pointer; font-size:12px; font-weight:650; }
.personnel-primary:hover:not(:disabled) { background:#375d6d; }
.personnel-secondary { border-color:#cbd7d8; background:#fff; color:var(--hnust-blue-deep); }
.personnel-primary:disabled,.personnel-secondary:disabled { cursor:wait; opacity:.58; }
.personnel-form-actions { display:flex; gap:8px; }
.personnel-list-heading { display:flex; justify-content:space-between; align-items:center; gap:16px; margin:24px 0 10px; }
.personnel-list-heading form,.import-lookup { display:flex; align-items:end; gap:8px; }
.personnel-pagination { display:flex; justify-content:space-between; align-items:center; gap:12px; margin:10px 0 22px; color:var(--hnust-muted); font-size:11px; }
.personnel-pagination > div { display:flex; gap:7px; }
.personnel-pagination .personnel-secondary { min-height:32px; }
.personnel-list-heading input,.import-lookup input { width:190px; }
.personnel-table-wrap { width:100%; overflow-x:auto; border:1px solid var(--hnust-line); border-radius:5px; background:#fff; }
.personnel-table-wrap table { width:100%; border-collapse:collapse; text-align:left; font-size:11px; }
.personnel-table-wrap th { padding:11px 9px; border-bottom:1px solid #dce7ea; background:#eaf2f5; color:#526d78; font-size:10px; font-weight:700; white-space:nowrap; }
.personnel-table-wrap td { padding:11px 9px; border-top:1px solid #e7eceb; color:#405862; vertical-align:top; }
.personnel-table-wrap tbody tr:hover { background:#f2f7f8; }
.personnel-table-wrap td small { display:block; max-width:260px; margin-top:4px; color:var(--hnust-faint); white-space:normal; }
.table-empty { padding:20px!important; color:var(--hnust-faint)!important; text-align:center; }
.table-actions { display:flex; gap:9px; white-space:nowrap; }
.personnel-link { padding:0; border:0; background:transparent; color:var(--hnust-blue-dark); cursor:pointer; font-size:11px; }
.credential-notice { display:grid; gap:7px; margin:15px 0 22px; padding:14px; border-left:3px solid #789886; background:#edf3ef; color:#496756; font-size:11px; }
.credential-notice code { overflow-wrap:anywhere; padding:8px; background:#fff; color:#405b4b; font-size:13px; }
.credential-warning { padding:12px; background:#f3f0e7; color:#766340; font-size:11px; line-height:1.7; }
.personnel-message { margin:14px 0; padding:10px 12px; border-radius:4px; font-size:12px; line-height:1.6; }
.personnel-error { border-left:3px solid #ba8585; background:#f4ebeb; color:#805959; }
.personnel-success { border-left:3px solid #81a58f; background:#eaf2ed; color:#4f705e; }
.personnel-empty { padding:20px; border:1px dashed #cadde4; border-radius:4px; background:#edf3f5; color:var(--hnust-muted); font-size:12px; }
.found-person,.eligibility-toolbar,.import-summary { display:flex; align-items:center; justify-content:space-between; gap:12px; margin:0 0 15px; padding:10px 12px; border-left:3px solid var(--hnust-blue); border-radius:4px; background:#eaf2f5; color:#4e6873; font-size:11px; }
.lookup-button { align-self:end; }
.history-toggle { display:flex; align-items:center; gap:7px; color:var(--hnust-muted); font-size:11px; }
.history-toggle input { accent-color:var(--hnust-blue-dark); }
.import-lookup { justify-content:flex-start; margin:18px 0; }
.credential-cell { max-width:240px; color:#4f705e!important; font-family:ui-monospace,Consolas,monospace; overflow-wrap:anywhere; }
@media(max-width:720px) { .personnel-heading { align-items:stretch; flex-direction:column; } .college-picker { min-width:0; } .personnel-form { grid-template-columns:1fr; } .personnel-form .wide-field { grid-column:auto; } .personnel-list-heading { align-items:stretch; flex-direction:column; } .personnel-list-heading input { width:100%; } .personnel-pagination { align-items:flex-start; flex-direction:column; } }
</style>
