<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ApiError } from '../../api/http'
import EmailSecurityPanel from '../auth/EmailSecurityPanel.vue'
import type { AuthUser } from '../../types/api'
import type {
  CorrectionStatus, DegreeType, IdentityCorrectionRequest, MatchStatus, PreferenceItem,
  PreferenceSubmission, StudentBatchSummary, StudentNotice, StudentPreferences, StudentProfile,
  StudentProgress, StudentRoundProgress, SupplementApplication, TeacherDetail, TeacherDirectoryItem,
} from '../../types/student'
import {
  confirmStudentIdentity, createIdentityCorrectionRequest, getStudentPreferences, getStudentProfile,
  getStudentProgress, getTeacherDetail, listIdentityCorrectionRequests, listPreferenceSubmissions,
  listStudentBatches, listStudentMajors, listStudentNotices, listSupplementApplications, listTeachers,
  markStudentNoticeRead, studentFileUrl, submitStudentPreferences, submitSupplementApplication,
  updateStudentProfile, uploadStudentResume, withdrawStudentPreferences,
} from '../../services/studentPortalService'

type Section = 'overview' | 'teachers' | 'preferences' | 'progress' | 'supplement' | 'profile' | 'notices'
const props = defineProps<{ user: AuthUser }>()
const sections: Array<{ key: Section; label: string }> = [
  { key: 'overview', label: '互选概览' },
  { key: 'teachers', label: '导师目录' },
  { key: 'preferences', label: '我的志愿' },
  { key: 'progress', label: '进度与结果' },
  { key: 'supplement', label: '补选' },
  { key: 'profile', label: '个人资料' },
  { key: 'notices', label: '站内通知' },
]
const activeSection = ref<Section>('overview')
const directoryMode = ref<'PREFERENCE' | 'SUPPLEMENT'>('PREFERENCE')
const pageLoading = ref(true)
const refreshingBatches = ref(false)
const batchLoading = ref(false)
const teacherLoading = ref(false)
const submitting = ref(false)
const savingProfile = ref(false)
const uploadingResume = ref(false)
const updatingNoticeId = ref<number | null>(null)
const errorMessage = ref('')
const successMessage = ref('')
const profile = ref<StudentProfile | null>(null)
const batches = ref<StudentBatchSummary[]>([])
const selectedBatchId = ref<number | null>(null)
const progress = ref<StudentProgress | null>(null)
const preferences = ref<StudentPreferences | null>(null)
const preferenceHistory = ref<PreferenceSubmission[]>([])
const preferenceDraft = ref<PreferenceItem[]>([])
const draftChanged = ref(false)
const supplementApplications = ref<SupplementApplication[]>([])
const correctionRequests = ref<IdentityCorrectionRequest[]>([])
const notices = ref<StudentNotice[]>([])
const majors = ref<Array<{ majorId: number; majorCode: string; majorName: string }>>([])
const teachers = ref<TeacherDirectoryItem[]>([])
const teacherTotal = ref(0)
const teacherPage = ref(1)
const teacherDetail = ref<TeacherDetail | null>(null)
const teacherDetailLoading = ref(false)
const identityChecked = ref(false)
const profileForm = reactive({ biography: '', contact: '' })
const correctionForm = reactive({ majorId: '', degreeType: '' as DegreeType | '', explanation: '' })
const teacherFilter = reactive({ keyword: '', researchDirection: '', onlyApplicable: false })

const selectedBatch = computed(() => batches.value.find((item) => item.batchId === selectedBatchId.value) ?? null)
const canEditPreferences = computed(() => selectedBatch.value?.actions.canSubmitPreferences ?? false)
const canWithdrawPreferences = computed(() => selectedBatch.value?.actions.canWithdrawPreferences ?? false)
const canApplySupplement = computed(() => selectedBatch.value?.actions.canApplySupplement ?? false)
const pendingSupplement = computed(() => supplementApplications.value.some((item) => item.status === 'IN_REVIEW'))
const unreadCount = computed(() => notices.value.filter((item) => !item.readAt).length)
const teacherPageCount = computed(() => Math.max(1, Math.ceil(teacherTotal.value / 20)))
const suggestedSection = computed<Section>(() => {
  if (!selectedBatch.value) return 'profile'
  if (selectedBatch.value.actions.canConfirmIdentity) return 'profile'
  if (canEditPreferences.value) return 'preferences'
  if (canApplySupplement.value) return 'supplement'
  return 'progress'
})
const currentStageText = computed(() => stageLabel(selectedBatch.value?.currentStage?.stageCode ?? null))
const primaryActionText = computed(() => {
  if (!selectedBatch.value) return '核对个人资料'
  if (selectedBatch.value.actions.canConfirmIdentity) return '核对并确认身份信息'
  if (canEditPreferences.value) return '查看并提交志愿'
  if (canApplySupplement.value) return '选择补选导师'
  return '查看互选进度'
})

function stageLabel(stage: string | null): string {
  const labels: Record<string, string> = {
    FILLING: '志愿填报', ROUND_1: '第一轮', ROUND_2: '第二轮', ROUND_3: '第三轮', SUPPLEMENT: '补选',
  }
  return stage ? labels[stage] ?? '互选阶段' : '阶段已结束'
}

function batchStatusText(status: StudentBatchSummary['batchStatus']): string {
  const labels: Record<StudentBatchSummary['batchStatus'], string> = {
    SCHEDULED: '即将开始', ACTIVE: '进行中', PAUSED: '暂时暂停',
    COMPLETED: '已完成', ARCHIVED: '已归档', CANCELLED: '已取消',
  }
  return labels[status]
}

function matchStatusText(status: MatchStatus | null): string {
  const labels: Record<MatchStatus, string> = {
    PENDING_ROUND_1: '等待第一轮', PENDING_ROUND_2: '等待第二轮', PENDING_ROUND_3: '等待第三轮',
    MATCHED: '已匹配', UNMATCHED: '暂未匹配',
  }
  return status ? labels[status] : '尚未进入办理'
}

function reasonText(reason: string | null): string {
  const labels: Record<string, string> = {
    NOT_SUBMITTED: '填报截止时未提交志愿',
    ROUND3_EXHAUSTED: '三轮志愿处理结束后仍未匹配',
    PREFERENCE_EXHAUSTED: '已填志愿顺位处理结束后仍未匹配',
    ALL_REMAINING_PREFERENCES_SKIPPED: '批次启动时已错过剩余志愿轮次',
    RELATION_REVOKED: '原匹配关系已由管理员按流程撤销',
    IDENTITY_CORRECTION: '身份信息更正后等待补选',
    BATCH_CANCELLED: '批次已取消',
  }
  return reason ? labels[reason] ?? '状态已更新' : ''
}

function roundText(round: StudentRoundProgress): string {
  if (round.resultPublished && round.state === 'ADMITTED') return '导师已录取'
  if (round.resultPublished && round.state === 'NOT_ADMITTED') return '导师未录取'
  const labels: Record<string, string> = {
    NO_PREFERENCE: '未填此顺位', WAITING: '等待本轮', PENDING: '待处理',
    PROCESSED: '已处理', SKIPPED_BY_SCHEDULE: '因启动时间跳过', CANCELLED_BY_BATCH: '批次已取消',
  }
  return labels[round.state] ?? '处理中'
}

function correctionText(status: CorrectionStatus): string {
  const labels: Record<CorrectionStatus, string> = { PENDING: '待管理员核验', APPROVED: '已通过', REJECTED: '未通过' }
  return labels[status]
}

function supplementText(status: SupplementApplication['status']): string {
  const labels: Record<SupplementApplication['status'], string> = {
    IN_REVIEW: '待导师处理', ADMITTED: '已录取', REJECTED: '未录取', CANCELLED_BY_BATCH: '批次已取消',
  }
  return labels[status]
}

function formatTime(value: string | null | undefined): string {
  if (!value) return '时间待定'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(date)
}

function hideTeacherPhoto(event: Event): void {
  const image = event.target as HTMLImageElement | null
  if (image) image.hidden = true
}

function preferenceButtonText(teacherId: number): string {
  const index = preferenceDraft.value.findIndex((item) => item.teacherId === teacherId)
  return index < 0 ? '加入志愿' : '已加入第 ' + (index + 1) + ' 志愿'
}

function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    const map: Record<string, string> = {
      STUDENT_CLASSIFICATION_CHANGED: '身份信息已更新，请重新核对后再办理。',
      SUPPLEMENT_PENDING_EXISTS: '你已有一条待处理的补选申请。',
      SUPPLEMENT_NOT_OPEN: '补选窗口当前未开放。',
      PREFERENCE_WINDOW_CLOSED: '志愿填报窗口已关闭。',
      PRECONDITION_FAILED: '资料已在其他页面更新，请刷新后再保存。',
      NOT_FOUND: '该内容当前不可查看，可能已结束或权限已变化。',
      FORBIDDEN: '当前账号暂时不能办理此操作。',
    }
    return map[error.code] ?? error.message
  }
  return '暂时无法连接服务，请检查网络后重试。'
}

async function initialize(): Promise<void> {
  pageLoading.value = true
  const results = await Promise.allSettled([
    getStudentProfile(), listStudentBatches(), listIdentityCorrectionRequests(), listStudentNotices(),
  ])
  const [profileResult, batchResult, correctionResult, noticeResult] = results
  if (profileResult.status === 'fulfilled') {
    profile.value = profileResult.value
    profileForm.biography = profileResult.value.biography ?? ''
    profileForm.contact = profileResult.value.contact ?? ''
  } else errorMessage.value = errorText(profileResult.reason)

  if (batchResult.status === 'fulfilled') {
    batches.value = batchResult.value.items
    selectedBatchId.value = batches.value[0]?.batchId ?? null
  } else if (!errorMessage.value) errorMessage.value = errorText(batchResult.reason)

  if (correctionResult.status === 'fulfilled') correctionRequests.value = correctionResult.value.items
  if (noticeResult.status === 'fulfilled') notices.value = noticeResult.value.items
  pageLoading.value = false

  if (profile.value) {
    try { majors.value = (await listStudentMajors(profile.value.collegeId)).items }
    catch { majors.value = [] }
  }
}

async function loadBatch(batchId: number): Promise<void> {
  batchLoading.value = true
  const results = await Promise.allSettled([
    getStudentProgress(batchId), getStudentPreferences(batchId),
    listPreferenceSubmissions(batchId), listSupplementApplications(batchId),
  ])
  const [progressResult, preferenceResult, historyResult, supplementResult] = results
  progress.value = progressResult.status === 'fulfilled' ? progressResult.value : null
  preferences.value = preferenceResult.status === 'fulfilled' ? preferenceResult.value : null
  if (!draftChanged.value) preferenceDraft.value = preferences.value?.items.map((item) => ({ ...item })) ?? []
  if (historyResult.status === 'fulfilled') preferenceHistory.value = historyResult.value.items
  if (supplementResult.status === 'fulfilled') supplementApplications.value = supplementResult.value.items
  batchLoading.value = false
}

watch(selectedBatchId, (batchId) => {
  if (!batchId) return
  draftChanged.value = false
  void loadBatch(batchId)
  if (activeSection.value === 'teachers' || (activeSection.value === 'preferences' && canEditPreferences.value)) void loadTeachers()
})
watch(activeSection, (section) => {
  errorMessage.value = ''
  successMessage.value = ''
  if (section === 'teachers' || (section === 'preferences' && canEditPreferences.value)) void loadTeachers()
})

function openSection(section: Section, mode: 'PREFERENCE' | 'SUPPLEMENT' = 'PREFERENCE'): void {
  activeSection.value = section
  if (section === 'teachers') directoryMode.value = mode
  errorMessage.value = ''
  successMessage.value = ''
}

async function reloadBatches(): Promise<void> {
  refreshingBatches.value = true
  errorMessage.value = ''
  try {
    const response = await listStudentBatches()
    batches.value = response.items
    if (!selectedBatchId.value || !batches.value.some((item) => item.batchId === selectedBatchId.value)) {
      selectedBatchId.value = batches.value[0]?.batchId ?? null
    }
  } catch (error) { errorMessage.value = errorText(error) }
  finally { refreshingBatches.value = false }
}

async function loadTeachers(): Promise<void> {
  if (!selectedBatchId.value) return
  if (activeSection.value === 'preferences' && !canEditPreferences.value) {
    teachers.value = []
    teacherTotal.value = 0
    return
  }
  teacherLoading.value = true
  try {
    const response = await listTeachers({
      batchId: selectedBatchId.value,
      keyword: teacherFilter.keyword.trim() || undefined,
      researchDirection: teacherFilter.researchDirection.trim() || undefined,
      canApply: activeSection.value === 'preferences' || directoryMode.value === 'SUPPLEMENT' || teacherFilter.onlyApplicable ? true : undefined,
      pageNo: teacherPage.value,
      pageSize: 20,
    })
    teachers.value = response.items
    teacherTotal.value = response.total
  } catch (error) {
    teachers.value = []
    teacherTotal.value = 0
    errorMessage.value = errorText(error)
  } finally { teacherLoading.value = false }
}

function searchTeachers(): void {
  teacherPage.value = 1
  void loadTeachers()
}
function changeTeacherPage(page: number): void {
  if (page < 1 || page > Math.ceil(teacherTotal.value / 20)) return
  teacherPage.value = page
  void loadTeachers()
}
async function showTeacher(teacherId: number): Promise<void> {
  if (!selectedBatchId.value) return
  teacherDetailLoading.value = true
  try {
    teacherDetail.value = await getTeacherDetail(teacherId, selectedBatchId.value)
    teachers.value = teachers.value.map((teacher) => teacher.teacherId === teacherId && teacherDetail.value
      ? { ...teacher, officialProfile: teacherDetail.value.officialProfile, researchDirections: teacherDetail.value.researchDirections,
          profileSummary: teacherDetail.value.profileSummary }
      : teacher)
  }
  catch (error) { errorMessage.value = errorText(error) }
  finally { teacherDetailLoading.value = false }
}

function addPreference(teacher: TeacherDirectoryItem): void {
  if (!canEditPreferences.value || !teacher.canApply) return
  if (preferenceDraft.value.some((item) => item.teacherId === teacher.teacherId)) return
  if (preferenceDraft.value.length >= 3) {
    errorMessage.value = '一份志愿最多选择三位不同导师。'
    return
  }
  preferenceDraft.value.push({
    teacherId: teacher.teacherId,
    preferenceOrder: preferenceDraft.value.length + 1,
    teacherName: teacher.displayName,
    employeeNo: teacher.employeeNo,
    researchDirections: teacher.researchDirections,
  })
  draftChanged.value = true
  errorMessage.value = ''
}

function removePreference(teacherId: number): void {
  preferenceDraft.value = preferenceDraft.value.filter((item) => item.teacherId !== teacherId)
    .map((item, index) => ({ ...item, preferenceOrder: index + 1 }))
  draftChanged.value = true
}
function movePreference(index: number, offset: -1 | 1): void {
  const target = index + offset
  if (target < 0 || target >= preferenceDraft.value.length) return
  const reordered = [...preferenceDraft.value]
  const current = reordered[index]
  reordered[index] = reordered[target]
  reordered[target] = current
  preferenceDraft.value = reordered.map((item, itemIndex) => ({ ...item, preferenceOrder: itemIndex + 1 }))
  draftChanged.value = true
}

async function confirmIdentity(): Promise<void> {
  if (!selectedBatch.value || !profile.value || !selectedBatch.value.actions.canConfirmIdentity) return
  if (!identityChecked.value) {
    errorMessage.value = '请先核对专业和学位类型，并勾选确认。'
    return
  }
  submitting.value = true
  errorMessage.value = ''
  try {
    await confirmStudentIdentity(selectedBatch.value.batchId, profile.value.classificationVersion)
    successMessage.value = '身份信息已确认，可以继续办理本批次业务。'
    identityChecked.value = false
    await reloadBatches()
    await loadTeachers()
  } catch (error) { errorMessage.value = errorText(error) }
  finally { submitting.value = false }
}

async function savePreferences(): Promise<void> {
  if (!selectedBatch.value || !profile.value || !canEditPreferences.value) return
  if (preferenceDraft.value.length < 1 || preferenceDraft.value.length > 3) {
    errorMessage.value = '请选择一至三位导师后再提交。'
    return
  }
  submitting.value = true
  errorMessage.value = ''
  try {
    await submitStudentPreferences(
      selectedBatch.value.batchId, profile.value.classificationVersion,
      preferenceDraft.value.map((item, index) => ({ teacherId: item.teacherId, preferenceOrder: index + 1 })),
    )
    draftChanged.value = false
    successMessage.value = '志愿已保存。填报截止前可以继续修改或撤回。'
    await loadBatch(selectedBatch.value.batchId)
    await reloadBatches()
  } catch (error) { errorMessage.value = errorText(error) }
  finally { submitting.value = false }
}

async function withdrawPreferences(): Promise<void> {
  if (!selectedBatch.value || !canWithdrawPreferences.value) return
  if (!window.confirm('撤回当前志愿后，本批次将没有有效志愿。要继续吗？')) return
  submitting.value = true
  errorMessage.value = ''
  try {
    await withdrawStudentPreferences(selectedBatch.value.batchId)
    preferenceDraft.value = []
    draftChanged.value = false
    successMessage.value = '志愿已撤回。填报截止前仍可重新提交。'
    await loadBatch(selectedBatch.value.batchId)
    await reloadBatches()
  } catch (error) { errorMessage.value = errorText(error) }
  finally { submitting.value = false }
}

async function applySupplement(teacher: TeacherDirectoryItem): Promise<void> {
  if (!selectedBatch.value || !canApplySupplement.value || !teacher.canApply || pendingSupplement.value) return
  if (!window.confirm('确认向 ' + teacher.displayName + ' 提交补选申请吗？')) return
  submitting.value = true
  errorMessage.value = ''
  try {
    await submitSupplementApplication(selectedBatch.value.batchId, teacher.teacherId)
    successMessage.value = '补选申请已提交。导师处理后会通过站内通知告知结果。'
    await loadBatch(selectedBatch.value.batchId)
    await reloadBatches()
  } catch (error) { errorMessage.value = errorText(error) }
  finally { submitting.value = false }
}

async function saveProfile(): Promise<void> {
  if (!profile.value) return
  savingProfile.value = true
  errorMessage.value = ''
  try {
    profile.value = await updateStudentProfile(profile.value.profileEtag, profileForm)
    profileForm.biography = profile.value.biography ?? ''
    profileForm.contact = profile.value.contact ?? ''
    successMessage.value = '个人资料已保存。'
  } catch (error) { errorMessage.value = errorText(error) }
  finally { savingProfile.value = false }
}

async function uploadResume(event: Event): Promise<void> {
  if (!profile.value) return
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (!file.name.toLowerCase().endsWith('.pdf')) {
    errorMessage.value = '简历只接受 PDF 文件。'
    return
  }
  if (file.size > 10 * 1024 * 1024) {
    errorMessage.value = '简历文件不能超过 10 MB。'
    return
  }
  uploadingResume.value = true
  errorMessage.value = ''
  try {
    await uploadStudentResume(file)
    profile.value = await getStudentProfile()
    successMessage.value = '简历已上传，扫描通过后导师才能查看。'
  } catch (error) { errorMessage.value = errorText(error) }
  finally { uploadingResume.value = false }
}

async function submitCorrection(): Promise<void> {
  if (!correctionForm.majorId && !correctionForm.degreeType) {
    errorMessage.value = '请选择需要更正的专业或学位类型。'
    return
  }
  if (!correctionForm.explanation.trim()) {
    errorMessage.value = '请说明需要更正的原因，方便管理员核对。'
    return
  }
  submitting.value = true
  errorMessage.value = ''
  try {
    const payload: { requestedMajorId?: number; requestedDegreeType?: DegreeType; studentExplanation: string } = {
      studentExplanation: correctionForm.explanation.trim(),
    }
    if (correctionForm.majorId) payload.requestedMajorId = Number(correctionForm.majorId)
    if (correctionForm.degreeType) payload.requestedDegreeType = correctionForm.degreeType
    await createIdentityCorrectionRequest(payload)
    correctionForm.majorId = ''
    correctionForm.degreeType = ''
    correctionForm.explanation = ''
    correctionRequests.value = (await listIdentityCorrectionRequests()).items
    successMessage.value = '更正申请已提交，可在本页查看处理状态。'
  } catch (error) { errorMessage.value = errorText(error) }
  finally { submitting.value = false }
}

async function markRead(notice: StudentNotice): Promise<void> {
  if (notice.readAt) return
  updatingNoticeId.value = notice.noticeId
  try {
    const result = await markStudentNoticeRead(notice.noticeId)
    notice.readAt = result.readAt
  } catch (error) { errorMessage.value = errorText(error) }
  finally { updatingNoticeId.value = null }
}

function stageComplete(stageCode: string): boolean {
  if (!selectedBatch.value) return false
  if (['COMPLETED', 'ARCHIVED'].includes(selectedBatch.value.batchStatus)) return true
  const sequence = ['FILLING', 'ROUND_1', 'ROUND_2', 'ROUND_3', 'SUPPLEMENT']
  const current = selectedBatch.value.currentStage?.stageCode
  const index = current ? sequence.indexOf(current) : -1
  return index > sequence.indexOf(stageCode)
    || (current === stageCode && selectedBatch.value.currentStage?.stageStatus === 'CLOSED')
}

onMounted(() => { void initialize() })
</script>
<template>
  <section class="student-workspace" aria-labelledby="student-workspace-title">
    <EmailSecurityPanel />
    <header class="student-heading">
      <div>
        <p class="student-kicker">学生服务</p>
        <h2 id="student-workspace-title">{{ profile?.fullName ?? props.user.identity?.displayName ?? '同学' }}，欢迎回来</h2>
        <p class="student-lede">核对身份、了解导师，并跟进本人的志愿和互选结果。</p>
      </div>
      <div v-if="batches.length" class="batch-picker">
        <label for="student-batch">互选批次</label>
        <select id="student-batch" v-model.number="selectedBatchId">
          <option v-for="batch in batches" :key="batch.batchId" :value="batch.batchId">
            {{ batch.academicYearName }} · {{ batch.batchName }}
          </option>
        </select>
        <span v-if="selectedBatch" class="batch-state">{{ batchStatusText(selectedBatch.batchStatus) }}</span>
      </div>
    </header>

    <nav class="student-navigation" aria-label="学生业务导航">
      <button
        v-for="section in sections"
        :key="section.key"
        type="button"
        :class="{ active: activeSection === section.key }"
        :aria-current="activeSection === section.key ? 'page' : undefined"
        @click="openSection(section.key)"
      >
        {{ section.label }}
        <span v-if="section.key === 'notices' && unreadCount" class="notice-count">{{ unreadCount }}</span>
      </button>
    </nav>

    <div v-if="errorMessage" class="student-alert error" role="alert">{{ errorMessage }}</div>
    <div v-if="successMessage" class="student-alert success" role="status">{{ successMessage }}</div>
    <div v-if="pageLoading" class="student-loading" aria-live="polite">
      <span class="loading-mark" aria-hidden="true"></span><p>正在读取你的互选信息</p>
    </div>

    <template v-else>
      <section v-if="activeSection === 'overview'" class="student-section">
        <div v-if="!selectedBatch" class="empty-batch">
          <p class="section-kicker">当前互选安排</p>
          <h3>暂时没有可办理的批次</h3>
          <p>本学院已发布批次只对该学年年度资格为“符合（ELIGIBLE）”且当前有效的学生显示。刚发布批次时请先刷新；若仍未显示，请联系管理员核对你的学院、学年和年度资格。</p>
          <button class="student-button" type="button" :disabled="refreshingBatches" @click="reloadBatches">
            {{ refreshingBatches ? '正在刷新…' : '刷新批次' }}
          </button>
          <button class="student-button primary" type="button" @click="openSection('profile')">查看个人资料</button>
        </div>

        <template v-else>
          <div class="current-stage">
            <div>
              <p class="section-kicker">{{ selectedBatch.academicYearName }} · {{ selectedBatch.batchName }}</p>
              <div class="stage-title-line">
                <span class="stage-emblem" aria-hidden="true">{{ selectedBatch.currentStage?.stageCode === 'FILLING' ? '志' : selectedBatch.currentStage?.stageCode?.startsWith('ROUND') ? '轮' : selectedBatch.currentStage?.stageCode === 'SUPPLEMENT' ? '补' : '互' }}</span>
                <div>
                  <h3>{{ currentStageText }}</h3>
                  <p v-if="selectedBatch.batchStatus === 'PAUSED'">批次暂时暂停，当前不能办理业务。</p>
                  <p v-else-if="selectedBatch.currentStage?.stageStatus === 'WAITING_FILLING'">填报窗口尚未开放，请先核对身份资料。</p>
                  <p v-else-if="selectedBatch.currentStage?.endAt">当前阶段截止：{{ formatTime(selectedBatch.currentStage.endAt) }}</p>
                  <p v-else>查看本人志愿和历史处理结果。</p>
                </div>
              </div>
            </div>
            <div class="stage-action">
              <span class="match-pill" :class="{ matched: selectedBatch.matchStatus === 'MATCHED', unmatched: selectedBatch.matchStatus === 'UNMATCHED' }">{{ matchStatusText(selectedBatch.matchStatus) }}</span>
              <button class="student-button primary" type="button" @click="openSection(suggestedSection)">{{ primaryActionText }}</button>
            </div>
          </div>

          <ol class="process-rail" aria-label="互选流程">
            <li :class="{ complete: stageComplete('FILLING'), active: selectedBatch.currentStage?.stageCode === 'FILLING' }"><span></span><small>志愿填报</small></li>
            <li :class="{ complete: stageComplete('ROUND_1'), active: selectedBatch.currentStage?.stageCode === 'ROUND_1' }"><span></span><small>第一轮</small></li>
            <li :class="{ complete: stageComplete('ROUND_2'), active: selectedBatch.currentStage?.stageCode === 'ROUND_2' }"><span></span><small>第二轮</small></li>
            <li :class="{ complete: stageComplete('ROUND_3'), active: selectedBatch.currentStage?.stageCode === 'ROUND_3' }"><span></span><small>第三轮</small></li>
            <li v-if="selectedBatch.supplementPlanned" :class="{ complete: stageComplete('SUPPLEMENT'), active: selectedBatch.currentStage?.stageCode === 'SUPPLEMENT' }"><span></span><small>补选</small></li>
          </ol>

          <div class="overview-grid">
            <article class="overview-panel">
              <div class="panel-heading">
                <div><p class="section-kicker">我的选择</p><h4>志愿顺序</h4></div>
                <button class="quiet-button" type="button" @click="openSection('preferences')">查看志愿</button>
              </div>
              <ol v-if="preferences?.items.length" class="preference-preview">
                <li v-for="item in preferences.items" :key="item.teacherId">
                  <span class="rank-number">{{ item.preferenceOrder }}</span>
                  <span><strong>{{ item.teacherName }}</strong><small>{{ item.researchDirections?.join('、') || '导师公开资料' }}</small></span>
                </li>
              </ol>
              <p v-else class="empty-note">还没有提交本批次志愿。</p>
            </article>

            <article class="overview-panel">
              <div class="panel-heading">
                <div><p class="section-kicker">填报前核对</p><h4>身份信息</h4></div>
                <button class="quiet-button" type="button" @click="openSection('profile')">个人资料</button>
              </div>
              <div v-if="profile" class="identity-lines">
                <p><span>学号</span><strong>{{ profile.studentNo }}</strong></p>
                <p><span>专业</span><strong>{{ profile.major.majorName }}</strong></p>
                <p><span>学位类型</span><strong>{{ profile.degreeType === 'ACADEMIC_MASTER' ? '学硕' : '专硕' }}</strong></p>
              </div>
              <p v-if="selectedBatch.actions.canConfirmIdentity" class="identity-reminder">请核对上方信息，并在填报前确认。</p>
              <p v-else-if="selectedBatch.confirmedClassificationVersion === selectedBatch.identityClassificationVersion" class="identity-confirmed">已确认当前身份版本。</p>
            </article>

            <article class="overview-panel notice-overview">
              <div class="panel-heading">
                <div><p class="section-kicker">系统消息</p><h4>最近通知</h4></div>
                <button class="quiet-button" type="button" @click="openSection('notices')">全部通知</button>
              </div>
              <p v-if="!notices.length" class="empty-note">暂无站内通知。</p>
              <button v-for="notice in notices.slice(0, 2)" :key="notice.noticeId" class="notice-preview-row" type="button" @click="openSection('notices')">
                <span class="notice-dot" :class="{ unread: !notice.readAt }"></span>
                <span><strong>{{ notice.title }}</strong><small>{{ formatTime(notice.sentAt) }}</small></span>
              </button>
            </article>
          </div>
        </template>
      </section>

      <section v-else-if="activeSection === 'teachers'" class="student-section">
        <div class="section-title-row">
          <div><p class="section-kicker">浏览与比较</p><h3>{{ directoryMode === 'SUPPLEMENT' ? '选择补选导师' : '导师目录' }}</h3><p class="section-copy">查看公开资料和本人的可填报状态。目录不显示导师剩余名额。</p></div>
        </div>
        <form class="teacher-filters" @submit.prevent="searchTeachers">
          <label><span>导师姓名</span><input v-model="teacherFilter.keyword" type="search" placeholder="输入导师姓名" /></label>
          <label><span>研究方向</span><input v-model="teacherFilter.researchDirection" type="search" placeholder="输入研究方向" /></label>
          <label v-if="directoryMode === 'PREFERENCE'" class="filter-check"><input v-model="teacherFilter.onlyApplicable" type="checkbox" /><span>只看可填报导师</span></label>
          <button class="student-button secondary" type="submit" :disabled="teacherLoading || !selectedBatch">{{ teacherLoading ? '正在查询…' : '筛选导师' }}</button>
        </form>
        <p v-if="!selectedBatch" class="empty-note">当前没有可查询的互选批次。</p>
        <div v-else-if="teacherLoading && !teachers.length" class="student-loading compact"><span class="loading-mark"></span><p>正在读取导师目录</p></div>
        <p v-else-if="!teacherLoading && !teachers.length" class="empty-note">没有符合当前筛选条件的导师。</p>
        <div v-else class="teacher-list">
          <article v-for="teacher in teachers" :key="teacher.teacherId" class="teacher-row">
            <span class="teacher-initial" aria-hidden="true"><span>{{ teacher.displayName.slice(0, 1) }}</span><img v-if="teacher.officialProfile?.photoUrl" :src="teacher.officialProfile.photoUrl" :alt="teacher.displayName + '照片'" @error="hideTeacherPhoto" /></span>
            <div class="teacher-main">
              <div class="teacher-name-line">
                <h4>{{ teacher.displayName }}</h4><span v-if="teacher.officialProfile?.professionalTitle">{{ teacher.officialProfile.professionalTitle }}</span>
                <span class="fillable-tag" :class="{ unavailable: !teacher.canApply }">{{ teacher.canApply ? '可填报' : '暂不可填报' }}</span>
              </div>
              <p class="teacher-directions">{{ teacher.researchDirections.join(' / ') || '研究方向待完善' }}</p>
              <p class="teacher-summary">{{ teacher.profileSummary || '暂无个人简介。' }}</p>
              <div class="teacher-scope">
                <span v-for="degree in teacher.allowedDegreeTypes" :key="degree">{{ degree === 'ACADEMIC_MASTER' ? '学硕' : '专硕' }}</span>
                <span v-for="major in teacher.allowedMajors" :key="major.majorId">{{ major.majorName }}</span>
              </div>
            </div>
            <div class="teacher-actions">
              <button class="quiet-button" type="button" @click="showTeacher(teacher.teacherId)">查看详情</button>
              <button v-if="directoryMode === 'PREFERENCE'" class="student-button small" type="button"
                :disabled="!teacher.canApply || !canEditPreferences || preferenceDraft.some((item) => item.teacherId === teacher.teacherId) || preferenceDraft.length >= 3"
                @click="addPreference(teacher)">
                {{ preferenceDraft.some((item) => item.teacherId === teacher.teacherId) ? '已加入' : '加入志愿' }}
              </button>
              <button v-else class="student-button small" type="button"
                :disabled="!teacher.canApply || !canApplySupplement || pendingSupplement || submitting"
                @click="applySupplement(teacher)">
                {{ pendingSupplement ? '已有待处理申请' : '申请补选' }}
              </button>
            </div>
          </article>
        </div>
        <div v-if="teacherTotal > 20" class="pagination-row">
          <button class="quiet-button" type="button" :disabled="teacherPage <= 1" @click="changeTeacherPage(teacherPage - 1)">上一页</button>
          <span>第 {{ teacherPage }} / {{ teacherPageCount }} 页，共 {{ teacherTotal }} 位导师</span>
          <button class="quiet-button" type="button" :disabled="teacherPage >= teacherPageCount" @click="changeTeacherPage(teacherPage + 1)">下一页</button>
        </div>
      </section>

      <section v-else-if="activeSection === 'preferences'" class="student-section">
        <div class="section-title-row">
          <div><p class="section-kicker">一至三位不同导师</p><h3>我的志愿</h3><p class="section-copy">查看适合本人身份的导师资料，直接加入并排列志愿；导师只会在对应轮次看到投向自己的申请。</p></div>
        </div>
        <div v-if="!selectedBatch" class="empty-note">当前没有可办理的互选批次。</div>
        <template v-else>
          <div v-if="selectedBatch.actions.canConfirmIdentity" class="identity-confirm-box">
            <div><strong>请先核对身份信息</strong><p>志愿填报前须确认当前专业和学位类型。身份信息如有错误，请先提交更正申请。</p></div>
            <label class="confirm-check"><input v-model="identityChecked" type="checkbox" />我已核对，信息无误</label>
            <button class="student-button secondary" type="button" :disabled="submitting" @click="confirmIdentity">{{ submitting ? '正在确认…' : '确认身份信息' }}</button>
          </div>
          <section v-if="canEditPreferences" class="preference-picker" aria-labelledby="preference-picker-title">
            <div class="preference-picker-heading">
              <div><p class="section-kicker">按当前专业与学位筛选</p><h4 id="preference-picker-title">可填报导师 <span>{{ teacherTotal }}</span></h4></div>
              <p>先查看导师公开资料，再加入志愿；最多选择三位。</p>
            </div>
            <form class="teacher-filters" @submit.prevent="searchTeachers">
              <label><span>导师姓名</span><input v-model="teacherFilter.keyword" type="search" placeholder="输入导师姓名" /></label>
              <label><span>研究方向</span><input v-model="teacherFilter.researchDirection" type="search" placeholder="输入研究方向" /></label>
              <button class="student-button secondary" type="submit" :disabled="teacherLoading">{{ teacherLoading ? '正在查询…' : '筛选导师' }}</button>
            </form>
            <div v-if="teacherLoading && !teachers.length" class="student-loading compact"><span class="loading-mark"></span><p>正在读取可填报导师</p></div>
            <p v-else-if="!teacherLoading && !teachers.length" class="empty-note">当前没有符合本人专业、学位和筛选条件的可填报导师。</p>
            <div v-else class="teacher-list preference-candidates">
              <article v-for="teacher in teachers" :key="teacher.teacherId" class="teacher-row preference-candidate">
                <span class="teacher-initial" aria-hidden="true"><span>{{ teacher.displayName.slice(0, 1) }}</span><img v-if="teacher.officialProfile?.photoUrl" :src="teacher.officialProfile.photoUrl" :alt="teacher.displayName + '照片'" @error="hideTeacherPhoto" /></span>
                <div class="teacher-main">
                  <div class="teacher-name-line"><h4>{{ teacher.displayName }}</h4><span v-if="teacher.officialProfile?.professionalTitle">{{ teacher.officialProfile.professionalTitle }}</span><span class="fillable-tag">可填报</span></div>
                  <p class="teacher-directions">{{ teacher.researchDirections.join(' / ') || '研究方向待完善' }}</p>
                  <p class="teacher-summary">{{ teacher.profileSummary || '导师简介待完善。可打开详情查看学校官网公开资料。' }}</p>
                  <div class="teacher-scope">
                    <span v-for="degree in teacher.allowedDegreeTypes" :key="degree">{{ degree === 'ACADEMIC_MASTER' ? '学硕' : '专硕' }}</span>
                    <span v-for="major in teacher.allowedMajors" :key="major.majorId">{{ major.majorName }}</span>
                  </div>
                </div>
                <div class="teacher-actions">
                  <button class="quiet-button" type="button" @click="showTeacher(teacher.teacherId)">详细资料</button>
                  <button class="student-button small" type="button"
                    :disabled="preferenceDraft.some((item) => item.teacherId === teacher.teacherId) || preferenceDraft.length >= 3 || submitting"
                    @click="addPreference(teacher)">
                    {{ preferenceButtonText(teacher.teacherId) }}
                  </button>
                </div>
              </article>
            </div>
            <div v-if="teacherTotal > 20" class="pagination-row">
              <button class="quiet-button" type="button" :disabled="teacherPage <= 1" @click="changeTeacherPage(teacherPage - 1)">上一页</button>
              <span>第 {{ teacherPage }} / {{ teacherPageCount }} 页，共 {{ teacherTotal }} 位可填报导师</span>
              <button class="quiet-button" type="button" :disabled="teacherPage >= teacherPageCount" @click="changeTeacherPage(teacherPage + 1)">下一页</button>
            </div>
          </section>
          <div class="preference-editor">
            <div class="preference-editor-head">
              <div><p class="section-kicker">当前版本</p><h4>{{ preferences?.preferenceStatus === 'LOCKED' ? '志愿已锁定' : '排列你的志愿' }}</h4></div>
              <span v-if="preferences?.versionNo" class="version-chip">第 {{ preferences.versionNo }} 版</span>
            </div>
            <ol class="preference-list">
              <li v-for="(item, index) in preferenceDraft" :key="item.teacherId">
                <span class="rank-number">{{ index + 1 }}</span>
                <div class="rank-copy"><strong>{{ item.teacherName }}</strong><small>{{ item.researchDirections?.join('、') || '研究方向待完善' }}</small></div>
                <div v-if="canEditPreferences" class="rank-controls">
                  <button class="icon-button" type="button" :disabled="index === 0" :aria-label="'将' + item.teacherName + '上移'" @click="movePreference(index, -1)">↑</button>
                  <button class="icon-button" type="button" :disabled="index === preferenceDraft.length - 1" :aria-label="'将' + item.teacherName + '下移'" @click="movePreference(index, 1)">↓</button>
                  <button class="remove-button" type="button" @click="removePreference(item.teacherId)">移除</button>
                </div>
              </li>
              <li v-for="slot in Math.max(0, 3 - preferenceDraft.length)" :key="'empty-' + slot" class="empty-slot">
                <span class="rank-number">{{ preferenceDraft.length + slot }}</span><span>选择导师后会出现在这里</span>
              </li>
            </ol>
            <div class="preference-footer">
              <p v-if="canEditPreferences">已选择 {{ preferenceDraft.length }} 位导师。志愿按第一、第二、第三轮依序处理。</p>
              <p v-else-if="preferences?.preferenceStatus === 'LOCKED'">填报窗口已关闭，志愿顺序已锁定。</p>
              <p v-else>当前阶段不能修改志愿。</p>
              <div class="preference-buttons">
                <button class="student-button primary" type="button" :disabled="!canEditPreferences || submitting" @click="savePreferences">
                  {{ submitting ? '正在保存…' : preferences?.preferenceStatus === 'SUBMITTED' ? '保存新版本' : '提交志愿' }}
                </button>
                <button v-if="canWithdrawPreferences && preferences?.preferenceStatus === 'SUBMITTED'" class="text-danger-button" type="button" :disabled="submitting" @click="withdrawPreferences">撤回志愿</button>
              </div>
            </div>
          </div>
          <details class="history-details">
            <summary>查看提交历史（{{ preferenceHistory.length }}）</summary>
            <ol v-if="preferenceHistory.length" class="history-list">
              <li v-for="submission in preferenceHistory" :key="submission.submissionId">
                <div><strong>第 {{ submission.versionNo }} 版</strong><span>{{ submission.status === 'LOCKED' ? '已锁定' : submission.status === 'WITHDRAWN' ? '已撤回' : '已提交' }}</span></div>
                <small>{{ formatTime(submission.submittedAt) }}</small>
                <p>{{ submission.items.map((item) => item.preferenceOrder + '. ' + item.teacherName).join('　') }}</p>
              </li>
            </ol>
            <p v-else class="empty-note">还没有提交记录。</p>
          </details>
        </template>
      </section>

      <section v-else-if="activeSection === 'progress'" class="student-section">
        <div class="section-title-row"><div><p class="section-kicker">仅本人可见</p><h3>进度与结果</h3><p class="section-copy">轮次进行中只显示待处理或已处理；轮次关闭后再显示录取结论。</p></div></div>
        <div v-if="!selectedBatch" class="empty-note">当前没有可查询的互选批次。</div>
        <div v-else-if="batchLoading" class="student-loading compact"><span class="loading-mark"></span><p>正在读取进度</p></div>
        <template v-else>
          <div class="progress-summary">
            <div><span>批次状态</span><strong>{{ batchStatusText(selectedBatch.batchStatus) }}</strong></div>
            <div><span>当前阶段</span><strong>{{ currentStageText }}</strong></div>
            <div><span>匹配状态</span><strong>{{ matchStatusText(progress?.matchStatus ?? selectedBatch.matchStatus) }}</strong></div>
          </div>
          <div v-if="progress?.currentRelation" class="matched-banner">
            <span aria-hidden="true">✓</span>
            <div><p class="section-kicker">关系已锁定</p><h4>{{ progress.currentRelation.teacherName }}</h4><p>{{ stageLabel(progress.currentRelation.source) }}录取 · {{ formatTime(progress.currentRelation.lockedAt) }}</p></div>
          </div>
          <p v-else-if="(progress?.matchStatus ?? selectedBatch.matchStatus) === 'UNMATCHED'" class="unmatched-banner">{{ reasonText(progress?.matchReason ?? selectedBatch.matchReason) }}</p>
          <ol v-if="progress?.rounds.length" class="round-list">
            <li v-for="round in progress.rounds" :key="round.roundNo">
              <span class="round-number">{{ round.roundNo }}</span>
              <div><strong>第 {{ round.roundNo }} 轮 <small v-if="round.teacherName">· {{ round.teacherName }}</small></strong><span>{{ roundText(round) }}</span></div>
              <time v-if="round.processedAt">{{ formatTime(round.processedAt) }}</time>
            </li>
          </ol>
          <p v-else class="empty-note">当前还没有轮次处理结果。</p>
        </template>
      </section>

      <section v-else-if="activeSection === 'supplement'" class="student-section">
        <div class="section-title-row"><div><p class="section-kicker">独立补选阶段</p><h3>补选</h3><p class="section-copy">补选不重新执行三轮。每次只向一位导师提交申请。</p></div></div>
        <div v-if="!selectedBatch" class="empty-note">当前没有可参与补选的批次。</div>
        <template v-else>
          <div class="supplement-status" :class="{ available: canApplySupplement }">
            <strong>{{ canApplySupplement ? '补选窗口开放' : '当前不能提交补选申请' }}</strong>
            <span>{{ selectedBatch.matchStatus === 'MATCHED' ? '已匹配学生不能通过补选更换导师。' : selectedBatch.matchStatus !== 'UNMATCHED' ? '只有当前状态为未匹配的学生可以参加补选。' : '可申请导师还须有补选资格、剩余名额，并符合本人的专业与学位范围。' }}</span>
          </div>
          <p v-if="pendingSupplement" class="pending-note">你有一条待处理的补选申请。导师处理后，可以在下方查看结果。</p>
          <h4 class="subsection-title">我的申请记录</h4>
          <ol v-if="supplementApplications.length" class="application-list">
            <li v-for="application in supplementApplications" :key="application.applicationId">
              <time>{{ formatTime(application.submittedAt) }}</time><strong>{{ application.teacherName }}</strong>
              <span class="application-status" :class="{ pending: application.status === 'IN_REVIEW', success: application.status === 'ADMITTED' }">{{ supplementText(application.status) }}</span>
            </li>
          </ol>
          <p v-else class="empty-note">还没有补选申请记录。</p>
          <div class="supplement-action">
            <div><h4>查看可申请导师</h4><p>导师目录会根据当前身份和批次范围计算可申请状态，不展示剩余名额数量。</p></div>
            <button class="student-button primary" type="button" :disabled="!canApplySupplement || pendingSupplement" @click="openSection('teachers', 'SUPPLEMENT')">{{ pendingSupplement ? '已有待处理申请' : '选择补选导师' }}</button>
          </div>
        </template>
      </section>

      <section v-else-if="activeSection === 'profile'" class="student-section">
        <div class="section-title-row"><div><p class="section-kicker">本人资料</p><h3>个人资料</h3><p class="section-copy">姓名、学号、专业和学位类型由管理员维护；请核对身份信息，错误时提交更正申请。</p></div></div>
        <div v-if="profile" class="profile-grid">
          <div class="profile-card">
            <p class="section-kicker">身份信息 · 只读</p>
            <dl class="identity-data">
              <div><dt>姓名</dt><dd>{{ profile.fullName }}</dd></div>
              <div><dt>学号</dt><dd>{{ profile.studentNo }}</dd></div>
              <div><dt>学院</dt><dd>{{ profile.collegeName }}</dd></div>
              <div><dt>专业</dt><dd>{{ profile.major.majorName }}</dd></div>
              <div><dt>学位类型</dt><dd>{{ profile.degreeType === 'ACADEMIC_MASTER' ? '学硕' : '专硕' }}</dd></div>
            </dl>
          </div>
          <form class="profile-card profile-form" @submit.prevent="saveProfile">
            <p class="section-kicker">允许维护的资料</p>
            <label for="student-biography">个人简介</label>
            <textarea id="student-biography" v-model="profileForm.biography" rows="4"  placeholder="介绍学习经历、兴趣和希望深入的方向"></textarea>
            <label for="student-contact">联系方式</label>
            <input id="student-contact" v-model="profileForm.contact" type="text" autocomplete="tel" />
            <button class="student-button primary" type="submit" :disabled="savingProfile">{{ savingProfile ? '正在保存…' : '保存资料' }}</button>
          </form>
          <div class="profile-card resume-card">
            <p class="section-kicker">简历附件</p>
            <div v-if="profile.resume" class="resume-current">
              <strong>{{ profile.resume.fileName }}</strong>
              <small>{{ (profile.resume.sizeBytes / 1048576).toFixed(2) }} MB · {{ profile.resume.scanStatus === 'AVAILABLE' ? '可供导师查看' : profile.resume.scanStatus === 'PENDING' ? '安全扫描中' : '扫描未通过' }}</small>
              <a v-if="profile.resume.scanStatus === 'AVAILABLE'" :href="studentFileUrl(profile.resume.fileId)" target="_blank" rel="noopener">在线查看简历</a>
            </div>
            <p v-else class="empty-note">尚未上传简历。</p>
            <label class="file-button">
              <input type="file" accept="application/pdf,.pdf" :disabled="uploadingResume" @change="uploadResume" />
              <span>{{ uploadingResume ? '正在上传…' : profile.resume ? '上传新版本' : '上传 PDF 简历' }}</span>
            </label>
            <small class="field-note">仅接受 PDF，单文件不超过 10 MB。上传新版本不会覆盖历史附件。</small>
          </div>
        </div>
        <div v-if="selectedBatch?.actions.canConfirmIdentity && profile" class="identity-confirm-box">
          <div><strong>填报前身份核对</strong><p>请确认当前专业与学位类型准确无误。</p></div>
          <label class="confirm-check"><input v-model="identityChecked" type="checkbox" />我已核对，信息无误</label>
          <button class="student-button secondary" type="button" :disabled="submitting" @click="confirmIdentity">{{ submitting ? '正在确认…' : '确认身份信息' }}</button>
        </div>
        <div class="correction-panel">
          <div><p class="section-kicker">身份信息有误？</p><h4>提交更正申请</h4><p>专业和学位类型不能自行编辑。管理员核对后会更新身份版本，你需要重新确认新信息。</p></div>
          <form class="correction-form" @submit.prevent="submitCorrection">
            <label for="requested-major">更正后的专业</label>
            <select id="requested-major" v-model="correctionForm.majorId">
              <option value="">无需更正</option>
              <option v-for="major in majors" :key="major.majorId" :value="String(major.majorId)">{{ major.majorName }}（{{ major.majorCode }}）</option>
            </select>
            <label for="requested-degree">更正后的学位类型</label>
            <select id="requested-degree" v-model="correctionForm.degreeType">
              <option value="">无需更正</option><option value="ACADEMIC_MASTER">学硕</option><option value="PROFESSIONAL_MASTER">专硕</option>
            </select>
            <label for="correction-explanation">说明</label>
            <textarea id="correction-explanation" v-model="correctionForm.explanation" rows="3"  required placeholder="说明与学校记录不一致的地方"></textarea>
            <button class="student-button secondary" type="submit" :disabled="submitting">{{ submitting ? '正在提交…' : '提交更正申请' }}</button>
          </form>
        </div>
        <div class="correction-history">
          <h4>更正申请记录</h4>
          <ol v-if="correctionRequests.length">
            <li v-for="item in correctionRequests" :key="item.requestId">
              <div><strong>{{ item.requestedMajor?.majorName ?? '专业未申请更正' }} · {{ item.requestedDegreeType === 'ACADEMIC_MASTER' ? '学硕' : item.requestedDegreeType === 'PROFESSIONAL_MASTER' ? '专硕' : '学位类型未申请更正' }}</strong><span class="correction-status" :class="item.status.toLowerCase()">{{ correctionText(item.status) }}</span></div>
              <p>{{ item.studentExplanation }}</p>
              <small>提交于 {{ formatTime(item.submittedAt) }}<template v-if="item.handledAt"> · 处理于 {{ formatTime(item.handledAt) }}</template></small>
              <p v-if="item.handlingComment" class="handling-comment">处理意见：{{ item.handlingComment }}</p>
            </li>
          </ol>
          <p v-else class="empty-note">还没有身份更正申请。</p>
        </div>
      </section>

      <section v-else class="student-section">
        <div class="section-title-row"><div><p class="section-kicker">只向本人发送</p><h3>站内通知</h3><p class="section-copy">互选办理结果和系统消息会显示在这里。</p></div></div>
        <div v-if="notices.length" class="notice-list">
          <article v-for="notice in notices" :key="notice.noticeId" class="notice-item" :class="{ unread: !notice.readAt }">
            <span class="notice-dot" :class="{ unread: !notice.readAt }"></span>
            <div class="notice-copy"><div><h4>{{ notice.title }}</h4><time>{{ formatTime(notice.sentAt) }}</time></div><p>{{ notice.content }}</p></div>
            <button v-if="!notice.readAt" class="quiet-button" type="button" :disabled="updatingNoticeId === notice.noticeId" @click="markRead(notice)">{{ updatingNoticeId === notice.noticeId ? '正在更新…' : '标为已读' }}</button>
            <span v-else class="read-label">已读</span>
          </article>
        </div>
        <p v-else class="empty-note">暂无站内通知。</p>
      </section>
    </template>

    <div v-if="teacherDetail || teacherDetailLoading" class="detail-backdrop" @click.self="teacherDetail = null">
      <section class="teacher-detail" role="dialog" aria-modal="true" aria-labelledby="teacher-detail-title">
        <button class="detail-close" type="button" aria-label="关闭导师详情" @click="teacherDetail = null">×</button>
        <div v-if="teacherDetailLoading" class="student-loading compact"><span class="loading-mark"></span><p>正在读取导师资料</p></div>
        <template v-else-if="teacherDetail">
          <div class="teacher-detail-heading">
            <div class="teacher-detail-photo"><span aria-hidden="true">{{ teacherDetail.displayName.slice(0, 1) }}</span><img v-if="teacherDetail.officialProfile?.photoUrl" :src="teacherDetail.officialProfile.photoUrl" :alt="teacherDetail.displayName + '照片'" @error="hideTeacherPhoto" /></div>
            <div><p class="section-kicker">导师公开资料</p><h3 id="teacher-detail-title">{{ teacherDetail.displayName }}</h3><p class="teacher-detail-title">{{ [teacherDetail.officialProfile?.professionalTitle, teacherDetail.officialProfile?.department].filter(Boolean).join(' · ') || '导师' }}</p></div>
          </div>
          <p class="teacher-directions">{{ teacherDetail.researchDirections.join(' / ') || '研究方向待完善' }}</p>
          <div class="teacher-detail-section"><h4>个人简介</h4><p class="teacher-detail-bio">{{ teacherDetail.biography || teacherDetail.profileSummary || teacherDetail.officialProfile?.biography || '暂无个人简介。' }}</p></div>
          <div v-if="teacherDetail.officialProfile?.educationLevel || teacherDetail.officialProfile?.teachingLevel" class="teacher-detail-facts">
            <div v-if="teacherDetail.officialProfile?.educationLevel"><span>学历</span><strong>{{ teacherDetail.officialProfile.educationLevel }}</strong></div>
            <div v-if="teacherDetail.officialProfile?.teachingLevel"><span>执教层次</span><strong>{{ teacherDetail.officialProfile.teachingLevel }}</strong></div>
          </div>
          <div v-if="teacherDetail.officialProfile?.educationExperience" class="teacher-detail-section"><h4>学习经历</h4><p>{{ teacherDetail.officialProfile.educationExperience }}</p></div>
          <div v-if="teacherDetail.officialProfile?.workExperience" class="teacher-detail-section"><h4>工作经历</h4><p>{{ teacherDetail.officialProfile.workExperience }}</p></div>
          <div v-if="teacherDetail.officialProfile?.courses" class="teacher-detail-section"><h4>承担课程</h4><p>{{ teacherDetail.officialProfile.courses }}</p></div>
          <div v-if="teacherDetail.officialProfile?.researchAndAchievements" class="teacher-detail-section"><h4>科研与成果</h4><p>{{ teacherDetail.officialProfile.researchAndAchievements }}</p></div>
          <div class="teacher-scope">
            <span v-for="degree in teacherDetail.allowedDegreeTypes" :key="degree">{{ degree === 'ACADEMIC_MASTER' ? '学硕' : '专硕' }}</span>
            <span v-for="major in teacherDetail.allowedMajors" :key="major.majorId">{{ major.majorName }}</span>
          </div>
          <p class="teacher-detail-status">{{ teacherDetail.canApply ? '按当前身份和批次可填报' : '按当前身份或批次暂不可填报' }}</p>
          <div v-if="teacherDetail.officialProfile?.profileUrl" class="teacher-profile-attribution">
            <span>学校公开资料来源：{{ teacherDetail.officialProfile.sourceName || '湖南科技大学教师主页' }}<template v-if="teacherDetail.officialProfile.cachedAt"> · 更新于 {{ formatTime(teacherDetail.officialProfile.cachedAt) }}</template></span>
            <a :href="teacherDetail.officialProfile.profileUrl" target="_blank" rel="noopener noreferrer">访问官方主页 ↗</a>
          </div>
          <p v-else class="teacher-profile-attribution">页面所列系统资料来自审核通过的导师公开信息；学校主页暂未匹配到唯一记录。</p>
        </template>
      </section>
    </div>
  </section>
</template>
<style scoped>
.student-workspace {
  --student-blue: var(--hnust-blue-deep);
  --student-blue-mid: var(--hnust-blue-dark);
  --student-blue-light: var(--hnust-pale);
  --student-paper: var(--hnust-paper);
  --student-line: var(--hnust-line);
  --student-ink: var(--hnust-ink);
  --student-muted: var(--hnust-muted);
  --student-faint: var(--hnust-faint);
  --student-green: var(--hnust-success);
  --student-red: var(--hnust-danger);
  width: 100%;
  color: var(--student-ink);
  font-size: 13px;
}

.student-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  padding: 3px 0 21px;
  border-bottom: 1px solid var(--student-line);
}
.student-kicker, .section-kicker {
  margin: 0;
  color: var(--student-blue-mid);
  font-size: 10px;
  font-weight: 750;
  letter-spacing: .08em;
}
.student-heading h2 {
  margin: 7px 0 6px;
  color: var(--student-blue);
  font-size: 23px;
  font-weight: 750;
}
.student-lede, .section-copy {
  margin: 0;
  color: var(--student-muted);
  font-size: 12px;
  line-height: 1.8;
}
.batch-picker {
  display: grid;
  grid-template-columns: auto minmax(180px, 300px);
  align-items: center;
  gap: 8px 12px;
}
.batch-picker label, .teacher-filters label, .profile-form label, .correction-form label {
  color: var(--student-muted);
  font-size: 10px;
  font-weight: 700;
}
.batch-picker select, .teacher-filters input, .profile-form input, .profile-form textarea,
.correction-form select, .correction-form textarea {
  min-height: 40px;
  padding: 8px 10px;
  border: 1px solid var(--student-line);
  border-radius: 3px;
  background: var(--student-paper);
  color: var(--student-ink);
  font: inherit;
}
.batch-picker select:focus, .teacher-filters input:focus, .profile-form input:focus,
.profile-form textarea:focus, .correction-form select:focus, .correction-form textarea:focus,
.student-workspace button:focus-visible, .student-workspace a:focus-visible {
  outline: 3px solid rgba(79, 120, 138, .2);
  outline-offset: 2px;
}
.batch-state {
  grid-column: 2;
  color: var(--student-muted);
  font-size: 10px;
}
.student-navigation {
  display: flex;
  gap: 4px;
  overflow-x: auto;
  border-bottom: 1px solid var(--student-line);
  scrollbar-width: thin;
}
.student-navigation button {
  min-height: 48px;
  flex: 0 0 auto;
  padding: 0 13px;
  border: 0;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: var(--student-muted);
  cursor: pointer;
  font: inherit;
  font-size: 11px;
}
.student-navigation button:hover, .student-navigation button.active {
  border-bottom-color: var(--student-blue-mid);
  color: var(--student-blue);
}
.notice-count {
  display: inline-grid;
  min-width: 17px;
  height: 17px;
  place-items: center;
  margin-left: 5px;
  border-radius: 50%;
  background: var(--student-blue-light);
  color: var(--student-blue);
  font-size: 9px;
}
.student-section { padding: 24px 0 12px; }
.student-alert {
  margin: 14px 0 0;
  padding: 10px 13px;
  border-left: 3px solid;
  font-size: 11px;
  line-height: 1.65;
}
.student-alert.error { border-color: var(--student-red); background: #f7eeee; color: #754d4d; }
.student-alert.success { border-color: var(--student-green); background: #eef5f0; color: #496653; }
.student-loading {
  display: flex;
  min-height: 160px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--student-muted);
  font-size: 12px;
}
.student-loading p { margin: 12px 0 0; }
.student-loading.compact { min-height: 100px; }
.loading-mark {
  width: 24px;
  height: 24px;
  border: 2px solid var(--student-line);
  border-top-color: var(--student-blue-mid);
  border-radius: 50%;
  animation: student-spin 800ms linear infinite;
}
@keyframes student-spin { to { transform: rotate(360deg); } }

.empty-batch { padding: 30px 0 34px; }
.empty-batch h3, .section-title-row h3 {
  margin: 7px 0;
  color: var(--student-blue);
  font-size: 20px;
}
.empty-batch > p:not(.section-kicker) { max-width: 610px; color: var(--student-muted); line-height: 1.8; }
.current-stage {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 28px;
  min-height: 154px;
  padding: 23px 26px;
  border: 1px solid var(--student-line);
  border-left: 4px solid var(--student-blue-mid);
  background: var(--student-paper);
}
.stage-title-line { display: flex; align-items: center; gap: 14px; margin-top: 11px; }
.stage-emblem {
  display: grid;
  width: 46px;
  height: 46px;
  flex: 0 0 auto;
  place-items: center;
  border: 1px solid #bfd1d8;
  background: var(--student-blue-light);
  color: var(--student-blue);
  font-size: 20px;
  font-weight: 800;
}
.stage-title-line h3 { margin: 0; color: var(--student-blue); font-size: 21px; }
.stage-title-line p { margin: 6px 0 0; color: var(--student-muted); font-size: 11px; line-height: 1.7; }
.stage-action { display: flex; min-width: 188px; flex-direction: column; align-items: flex-end; gap: 12px; }
.match-pill, .fillable-tag, .application-status, .correction-status {
  display: inline-flex;
  align-items: center;
  min-height: 24px;
  padding: 3px 9px;
  border: 1px solid #cddde2;
  background: #edf4f6;
  color: var(--student-blue);
  font-size: 10px;
  font-weight: 700;
}
.match-pill.matched, .application-status.success, .correction-status.approved {
  border-color: #d0e1d5;
  background: #eff6f0;
  color: var(--student-green);
}
.match-pill.unmatched, .fillable-tag.unavailable {
  border-color: #e7dbd4;
  background: #f7f1ed;
  color: #85675c;
}
.student-button {
  display: inline-flex;
  min-height: 39px;
  align-items: center;
  justify-content: center;
  padding: 0 14px;
  border: 1px solid var(--student-blue);
  border-radius: 3px;
  background: var(--student-blue);
  color: #fff;
  cursor: pointer;
  font: inherit;
  font-size: 11px;
  font-weight: 700;
}
.student-button:hover:not(:disabled) { border-color: #3b6579; background: #3b6579; }
.student-button.secondary, .student-button.small {
  border-color: #c3d4db;
  background: #f4f8f9;
  color: var(--student-blue);
}
.student-button.secondary:hover:not(:disabled), .student-button.small:hover:not(:disabled) {
  border-color: #9fbac5;
  background: #e9f1f4;
}
.student-button.small { min-height: 31px; padding: 0 10px; font-size: 10px; }
.student-button:disabled, .quiet-button:disabled, .icon-button:disabled { cursor: not-allowed; opacity: .48; }

.process-rail {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 4px;
  margin: 25px 0 23px;
  padding: 0;
  list-style: none;
}
.process-rail li {
  position: relative;
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  color: var(--student-faint);
  text-align: center;
}
.process-rail li:not(:last-child)::after {
  position: absolute;
  top: 5px;
  left: calc(50% + 12px);
  width: calc(100% - 24px);
  height: 1px;
  background: var(--student-line);
  content: '';
}
.process-rail li > span {
  position: relative;
  z-index: 1;
  width: 11px;
  height: 11px;
  border: 2px solid #b9cbd1;
  border-radius: 50%;
  background: #f5f8f9;
}
.process-rail li.active { color: var(--student-blue); font-weight: 750; }
.process-rail li.active > span { border-color: var(--student-blue-mid); background: var(--student-blue-mid); box-shadow: 0 0 0 4px #e7f0f3; }
.process-rail li.complete > span { border-color: var(--student-green); background: var(--student-green); }

.overview-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 13px; }
.overview-panel, .profile-card {
  min-width: 0;
  padding: 17px 18px;
  border: 1px solid var(--student-line);
  background: var(--student-paper);
}
.panel-heading, .section-title-row, .preference-editor-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 15px;
}
.panel-heading h4, .preference-editor-head h4 { margin: 5px 0 0; color: var(--student-blue); font-size: 15px; }
.quiet-button, .remove-button, .text-danger-button {
  padding: 5px 0;
  border: 0;
  background: transparent;
  color: var(--student-blue-mid);
  cursor: pointer;
  font: inherit;
  font-size: 10px;
  font-weight: 700;
}
.quiet-button:hover:not(:disabled), .remove-button:hover, .text-danger-button:hover {
  color: var(--student-blue);
  text-decoration: underline;
  text-underline-offset: 3px;
}
.preference-preview { margin: 13px 0 0; padding: 0; list-style: none; }
.preference-preview li { display: flex; align-items: center; gap: 11px; padding: 10px 0; border-top: 1px solid #edf1f2; }
.rank-number {
  display: grid;
  width: 29px;
  height: 29px;
  flex: 0 0 auto;
  place-items: center;
  border: 1px solid #c5d7dd;
  background: #f3f7f8;
  color: var(--student-blue);
  font-weight: 750;
}
.preference-preview strong, .rank-copy strong { display: block; color: var(--student-blue); font-size: 12px; }
.preference-preview small, .rank-copy small { display: block; margin-top: 4px; color: var(--student-faint); font-size: 10px; }
.identity-lines { margin-top: 12px; }
.identity-lines p {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin: 0;
  padding: 7px 0;
  border-top: 1px solid #edf1f2;
  font-size: 11px;
}
.identity-lines span { color: var(--student-muted); }
.identity-lines strong { color: var(--student-ink); font-weight: 650; }
.identity-reminder, .identity-confirmed { margin: 10px 0 0; color: #7a6750; font-size: 10px; }
.identity-confirmed { color: var(--student-green); }
.notice-overview { grid-column: 1 / -1; }
.notice-preview-row {
  display: flex;
  width: 100%;
  align-items: flex-start;
  gap: 10px;
  padding: 11px 0 0;
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
  text-align: left;
}
.notice-preview-row strong { display: block; color: var(--student-ink); font-size: 11px; }
.notice-preview-row small { display: block; margin-top: 4px; color: var(--student-faint); font-size: 9px; }
.notice-dot {
  display: inline-block;
  width: 7px;
  height: 7px;
  flex: 0 0 auto;
  margin-top: 5px;
  border: 1px solid #b8c7cb;
  border-radius: 50%;
}
.notice-dot.unread { border-color: var(--student-blue-mid); background: var(--student-blue-mid); }
.empty-note { margin: 12px 0 0; color: var(--student-faint); font-size: 11px; line-height: 1.8; }

.section-title-row { align-items: center; margin-bottom: 17px; }
.section-title-row h3 { margin: 5px 0 3px; }
.teacher-filters {
  display: grid;
  grid-template-columns: minmax(160px, 1fr) minmax(160px, 1fr) auto auto;
  align-items: end;
  gap: 11px;
  margin-bottom: 17px;
  padding: 14px;
  border: 1px solid var(--student-line);
  background: #f8fafb;
}
.teacher-filters label { display: grid; gap: 6px; }
.teacher-filters .filter-check { display: flex; min-height: 40px; align-items: center; gap: 7px; white-space: nowrap; }
.teacher-filters input[type='checkbox'], .confirm-check input { accent-color: var(--student-blue-mid); }
.preference-picker { margin: 0 0 16px; padding: 17px 18px 6px; border: 1px solid var(--student-line); background: #fbfcfc; }
.preference-picker-heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 14px; margin-bottom: 12px; }
.preference-picker-heading h4 { margin: 4px 0 0; color: var(--student-blue); font-size: 15px; }
.preference-picker-heading h4 span { margin-left: 4px; color: var(--student-faint); font-size: 11px; font-weight: 500; }
.preference-picker-heading > p { margin: 0 0 2px; color: var(--student-muted); font-size: 10px; }
.preference-picker .teacher-filters { grid-template-columns: minmax(160px, 1fr) minmax(160px, 1fr) auto; padding: 0 0 13px; border: 0; background: transparent; }
.preference-candidates { border-top: 1px solid var(--student-line); }
.preference-candidate { padding: 14px 3px; }
.teacher-list { border-top: 1px solid var(--student-line); }
.teacher-row {
  display: grid;
  grid-template-columns: 41px minmax(0, 1fr) auto;
  align-items: start;
  gap: 14px;
  padding: 17px 4px;
  border-bottom: 1px solid var(--student-line);
}
.teacher-initial {
  position: relative;
  overflow: hidden;
  display: grid;
  width: 40px;
  height: 40px;
  place-items: center;
  border: 1px solid #c5d7dd;
  background: var(--student-blue-light);
  color: var(--student-blue);
  font-size: 15px;
  font-weight: 750;
}
.teacher-initial img, .teacher-detail-photo img { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; }
.teacher-name-line { display: flex; flex-wrap: wrap; align-items: baseline; gap: 8px 11px; }
.teacher-name-line h4 { margin: 0; color: var(--student-blue); font-size: 14px; }
.teacher-name-line > span:not(.fillable-tag) { color: var(--student-faint); font-size: 10px; }
.teacher-directions { margin: 7px 0 0; color: var(--student-blue-mid); font-size: 10px; font-weight: 650; }
.teacher-summary { margin: 8px 0 0; color: var(--student-muted); font-size: 11px; line-height: 1.7; }
.teacher-scope { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 9px; }
.teacher-scope span { padding: 4px 7px; border: 1px solid #d7e3e6; background: #f7fafb; color: #63777e; font-size: 9px; }
.teacher-actions { display: flex; flex-direction: column; align-items: flex-end; gap: 8px; }
.pagination-row { display: flex; align-items: center; justify-content: center; gap: 18px; padding: 16px 0; color: var(--student-muted); font-size: 10px; }

.identity-confirm-box {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 14px;
  margin-bottom: 15px;
  padding: 14px;
  border: 1px solid #d9e1d5;
  border-left: 3px solid #7f957a;
  background: #f4f7f1;
}
.identity-confirm-box strong { color: #526b56; font-size: 12px; }
.identity-confirm-box p { margin: 5px 0 0; color: #738174; font-size: 10px; line-height: 1.7; }
.confirm-check { display: flex; align-items: center; gap: 7px; color: #526b56; font-size: 10px; white-space: nowrap; }
.preference-editor { border: 1px solid var(--student-line); background: var(--student-paper); }
.preference-editor-head { padding: 16px 18px; border-bottom: 1px solid var(--student-line); }
.version-chip { padding: 5px 8px; border: 1px solid var(--student-line); color: var(--student-muted); font-size: 9px; }
.preference-list { margin: 0; padding: 0 18px; list-style: none; }
.preference-list > li { display: flex; min-height: 61px; align-items: center; gap: 12px; border-bottom: 1px solid #edf1f2; }
.preference-list > li:last-child { border-bottom: 0; }
.rank-copy { min-width: 0; flex: 1; }
.rank-controls { display: flex; align-items: center; gap: 7px; }
.icon-button { width: 27px; height: 27px; border: 1px solid var(--student-line); background: #fff; color: var(--student-blue-mid); cursor: pointer; }
.remove-button, .text-danger-button { color: var(--student-red); }
.empty-slot { color: #a1afb3; font-size: 10px; }
.preference-footer { display: flex; align-items: center; justify-content: space-between; gap: 15px; padding: 13px 18px; border-top: 1px solid var(--student-line); background: #f8fafb; }
.preference-footer p { margin: 0; color: var(--student-muted); font-size: 10px; line-height: 1.7; }
.preference-buttons { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.history-details { margin-top: 14px; border-top: 1px solid var(--student-line); border-bottom: 1px solid var(--student-line); }
.history-details summary { padding: 13px 2px; color: var(--student-blue); cursor: pointer; font-size: 11px; font-weight: 700; }
.history-list { margin: 0; padding: 0; list-style: none; }
.history-list li { padding: 12px 0; border-top: 1px solid #e8eef0; }
.history-list li > div { display: flex; gap: 10px; color: var(--student-blue); font-size: 11px; }
.history-list li > div span, .history-list small { color: var(--student-faint); font-size: 9px; }
.history-list p { margin: 7px 0 0; color: var(--student-muted); font-size: 10px; }

.progress-summary { display: grid; grid-template-columns: repeat(3, 1fr); border: 1px solid var(--student-line); background: #fff; }
.progress-summary > div { display: grid; gap: 8px; padding: 14px 16px; border-right: 1px solid var(--student-line); }
.progress-summary > div:last-child { border-right: 0; }
.progress-summary span { color: var(--student-faint); font-size: 10px; }
.progress-summary strong { color: var(--student-blue); font-size: 12px; }
.matched-banner { display: flex; align-items: center; gap: 14px; margin-top: 14px; padding: 16px; border: 1px solid #d3e2d7; background: #f3f7f3; }
.matched-banner > span { display: grid; width: 37px; height: 37px; place-items: center; border: 1px solid #9fb7a4; border-radius: 50%; color: var(--student-green); font-size: 18px; }
.matched-banner h4 { margin: 5px 0; color: #496653; font-size: 15px; }
.matched-banner p:last-child { margin: 0; color: #718477; font-size: 10px; }
.unmatched-banner { margin: 14px 0 0; padding: 12px 14px; border-left: 3px solid #b59380; background: #f7f2ee; color: #755f52; font-size: 11px; }
.round-list { margin: 16px 0 0; padding: 0; border-top: 1px solid var(--student-line); list-style: none; }
.round-list li { display: flex; align-items: center; gap: 13px; min-height: 62px; border-bottom: 1px solid var(--student-line); }
.round-list li > div { display: flex; min-width: 0; flex: 1; flex-direction: column; gap: 5px; }
.round-list strong { color: var(--student-blue); font-size: 11px; }
.round-list strong small { font-size: 10px; font-weight: 500; }
.round-list li > div > span, .round-list time { color: var(--student-muted); font-size: 10px; }

.supplement-status { display: flex; flex-direction: column; gap: 5px; padding: 13px 15px; border-left: 3px solid #b6a47e; background: #f8f6ef; }
.supplement-status.available { border-left-color: var(--student-green); background: #f2f7f3; }
.supplement-status strong { color: #6c6348; font-size: 11px; }
.supplement-status.available strong { color: var(--student-green); }
.supplement-status span { color: var(--student-muted); font-size: 10px; line-height: 1.7; }
.pending-note { margin: 11px 0; padding: 10px 12px; border: 1px solid #d7e3e6; color: var(--student-blue); font-size: 10px; }
.subsection-title { margin: 20px 0 0; color: var(--student-blue); font-size: 12px; }
.application-list { margin: 14px 0 0; padding: 0; border-top: 1px solid var(--student-line); list-style: none; }
.application-list li { display: grid; grid-template-columns: 130px minmax(120px, 1fr) auto; align-items: center; gap: 10px; min-height: 50px; border-bottom: 1px solid var(--student-line); font-size: 11px; }
.application-list time { color: var(--student-faint); font-size: 9px; }
.application-list strong { color: var(--student-blue); }
.application-status.pending { border-color: #e2dccb; background: #f8f5ed; color: #7b6a47; }
.supplement-action { display: flex; align-items: center; justify-content: space-between; gap: 18px; margin-top: 21px; padding: 16px; border: 1px solid var(--student-line); background: #f8fafb; }
.supplement-action h4 { margin: 0; color: var(--student-blue); font-size: 12px; }
.supplement-action p { margin: 6px 0 0; color: var(--student-muted); font-size: 10px; line-height: 1.7; }

.profile-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.profile-card { padding: 16px; }
.identity-data { margin: 12px 0 0; }
.identity-data div { display: flex; justify-content: space-between; gap: 12px; padding: 8px 0; border-top: 1px solid #edf1f2; }
.identity-data dt { color: var(--student-muted); font-size: 10px; }
.identity-data dd { margin: 0; color: var(--student-ink); font-size: 10px; font-weight: 700; }
.profile-form, .correction-form { display: flex; flex-direction: column; gap: 8px; }
.profile-form label, .correction-form label { margin-top: 5px; }
.profile-form textarea, .correction-form textarea { resize: vertical; line-height: 1.7; }
.profile-form .student-button { align-self: flex-start; margin-top: 5px; }
.resume-card { grid-column: 1 / -1; }
.resume-current { display: flex; flex-direction: column; gap: 5px; margin-top: 11px; color: var(--student-blue); font-size: 11px; }
.resume-current small, .field-note { color: var(--student-faint); font-size: 9px; }
.resume-current a { width: fit-content; margin-top: 4px; color: var(--student-blue-mid); font-size: 10px; }
.file-button { position: relative; display: inline-flex; width: fit-content; min-height: 36px; align-items: center; margin-top: 13px; padding: 0 12px; border: 1px solid #c5d7dd; background: #f4f8f9; color: var(--student-blue); cursor: pointer; font-size: 10px; font-weight: 700; }
.file-button input { position: absolute; inset: 0; width: 100%; height: 100%; opacity: 0; cursor: pointer; }
.field-note { display: block; margin-top: 8px; }
.profile-confirm { margin-top: 14px; }
.correction-panel { display: grid; grid-template-columns: minmax(180px, .8fr) minmax(0, 1.2fr); gap: 20px; margin-top: 16px; padding: 17px; border: 1px solid var(--student-line); background: #fbfcfc; }
.correction-panel h4 { margin: 6px 0; color: var(--student-blue); font-size: 14px; }
.correction-panel > div > p:last-child { color: var(--student-muted); font-size: 10px; line-height: 1.8; }
.correction-history { margin-top: 18px; }
.correction-history h4 { margin: 0 0 8px; color: var(--student-blue); font-size: 12px; }
.correction-history ol { margin: 0; padding: 0; list-style: none; }
.correction-history li { padding: 12px 0; border-top: 1px solid var(--student-line); }
.correction-history li > div { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.correction-history li > div strong { color: var(--student-ink); font-size: 11px; }
.correction-status.pending { border-color: #e2dccb; background: #f8f5ed; color: #7b6a47; }
.correction-status.rejected { border-color: #e4d4d4; background: #f8eeee; color: var(--student-red); }
.correction-history li p { margin: 6px 0; color: var(--student-muted); font-size: 10px; line-height: 1.7; }
.correction-history li small { color: var(--student-faint); font-size: 9px; }
.handling-comment { padding-left: 9px; border-left: 2px solid var(--student-line); }

.notice-list { border-top: 1px solid var(--student-line); }
.notice-item { display: flex; align-items: flex-start; gap: 12px; padding: 15px 3px; border-bottom: 1px solid var(--student-line); }
.notice-item.unread { background: linear-gradient(90deg, #f1f6f7, transparent 75%); }
.notice-copy { min-width: 0; flex: 1; }
.notice-copy > div { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; }
.notice-copy h4 { margin: 0; color: var(--student-blue); font-size: 12px; }
.notice-copy time, .read-label { color: var(--student-faint); font-size: 9px; }
.notice-copy p { margin: 7px 0 0; color: var(--student-muted); font-size: 11px; line-height: 1.8; white-space: pre-wrap; }

.detail-backdrop { position: fixed; z-index: 40; inset: 0; display: grid; place-items: center; padding: 20px; background: rgba(30, 47, 54, .36); }
.teacher-detail { position: relative; width: min(100%, 680px); max-height: 86vh; overflow: auto; padding: 27px; border-top: 4px solid var(--student-blue-mid); background: #fff; box-shadow: 0 18px 55px rgba(25, 47, 58, .17); }
.detail-close { position: absolute; top: 9px; right: 12px; width: 32px; height: 32px; border: 0; background: transparent; color: var(--student-muted); cursor: pointer; font-size: 23px; }
.teacher-detail h3 { margin: 9px 0 3px; color: var(--student-blue); font-size: 21px; }
.teacher-detail-heading { display: flex; align-items: center; gap: 16px; padding-right: 28px; }
.teacher-detail-photo { position: relative; display: grid; width: 92px; height: 112px; flex: 0 0 auto; place-items: center; overflow: hidden; border: 1px solid #d4e0e3; background: var(--student-blue-light); color: var(--student-blue); font-size: 31px; font-weight: 750; }
.teacher-detail-title { margin: 5px 0 0; color: var(--student-muted); font-size: 11px; }
.teacher-detail-status { color: var(--student-muted); font-size: 10px; }
.teacher-detail-section { margin-top: 17px; padding-top: 13px; border-top: 1px solid var(--student-line); }
.teacher-detail-section h4 { margin: 0 0 7px; color: var(--student-blue); font-size: 12px; }
.teacher-detail-section > p { margin: 0; color: var(--student-ink); font-size: 11px; line-height: 1.85; white-space: pre-wrap; }
.teacher-detail-bio { margin: 15px 0; color: var(--student-ink); font-size: 12px; line-height: 1.9; white-space: pre-wrap; }
.teacher-detail-status { margin-top: 17px; color: var(--student-green); }
.teacher-detail-facts { display: flex; flex-wrap: wrap; gap: 20px; margin-top: 15px; }
.teacher-detail-facts div { display: grid; gap: 3px; }
.teacher-detail-facts span { color: var(--student-faint); font-size: 9px; }
.teacher-detail-facts strong { color: var(--student-ink); font-size: 11px; font-weight: 600; }
.teacher-profile-attribution { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px; margin-top: 18px; padding-top: 12px; border-top: 1px solid var(--student-line); color: var(--student-faint); font-size: 9px; line-height: 1.6; }
.teacher-profile-attribution a { color: var(--student-blue-mid); text-decoration: none; }
.teacher-profile-attribution a:hover { text-decoration: underline; }

@media (max-width: 850px) {
  .student-heading { align-items: flex-start; flex-direction: column; }
  .teacher-filters { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .preference-picker .teacher-filters { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .preference-picker-heading { align-items: flex-start; flex-direction: column; gap: 5px; }
  .profile-grid { grid-template-columns: 1fr; }
  .resume-card { grid-column: auto; }
}
@media (max-width: 620px) {
  .student-heading h2 { font-size: 20px; }
  .batch-picker { width: 100%; grid-template-columns: 1fr; gap: 6px; }
  .batch-state { grid-column: 1; }
  .student-navigation button { min-height: 43px; padding: 0 10px; }
  .current-stage { align-items: flex-start; flex-direction: column; gap: 15px; padding: 18px; }
  .stage-action { width: 100%; min-width: 0; align-items: flex-start; }
  .stage-title-line h3 { font-size: 18px; }
  .process-rail { gap: 0; }
  .process-rail li small { font-size: 8px; }
  .process-rail li:not(:last-child)::after { left: calc(50% + 8px); width: calc(100% - 16px); }
  .overview-grid, .teacher-filters, .profile-grid { grid-template-columns: 1fr; }
  .notice-overview, .resume-card { grid-column: auto; }
  .teacher-row { grid-template-columns: 34px minmax(0, 1fr); gap: 10px; }
  .teacher-initial { width: 34px; height: 34px; }
  .teacher-actions { grid-column: 2; flex-direction: row; align-items: center; justify-content: flex-start; }
  .teacher-name-line h4 { width: 100%; }
  .teacher-detail { padding: 23px 18px; }
  .teacher-detail-photo { width: 72px; height: 88px; }
  .pagination-row { gap: 10px; font-size: 9px; }
  .identity-confirm-box { grid-template-columns: 1fr; align-items: flex-start; }
  .preference-footer { align-items: flex-start; flex-direction: column; }
  .preference-buttons { width: 100%; }
  .preference-buttons .student-button { flex: 1; }
  .rank-controls { gap: 4px; }
  .progress-summary { grid-template-columns: 1fr; }
  .progress-summary > div { grid-template-columns: 100px 1fr; align-items: center; border-right: 0; border-bottom: 1px solid var(--student-line); }
  .progress-summary > div:last-child { border-bottom: 0; }
  .application-list li { grid-template-columns: 1fr auto; gap: 5px; padding: 9px 0; }
  .application-list time { grid-column: 1 / -1; }
  .supplement-action { align-items: flex-start; flex-direction: column; }
  .correction-panel { grid-template-columns: 1fr; gap: 9px; }
  .notice-item { flex-wrap: wrap; }
  .notice-copy > div { align-items: flex-start; flex-direction: column; gap: 4px; }
}
@media (prefers-reduced-motion: reduce) {
  .student-workspace *, .student-workspace *::before, .student-workspace *::after {
    scroll-behavior: auto !important;
    animation-duration: .01ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: .01ms !important;
  }
}
</style>
