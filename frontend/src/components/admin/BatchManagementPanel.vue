<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ApiError } from '../../api/http'
import UnmatchedStudentsDialog from './UnmatchedStudentsDialog.vue'
import {
  selectionBatchService,
  type AcademicYearOption,
  type BatchCollegeOption,
  type BatchDetail,
  type BatchMajor,
  type BatchStage,
  type BatchSummary,
  type BatchStatistics,
  type TeacherQuota,
} from '../../services/selectionBatchService'

const props = defineProps<{ canManage: boolean; canManagePersonnel?: boolean }>()

// 目录与当前选择分别保存：学院变化会重载学年/批次，批次变化会重载详情和导师名额。
const colleges = ref<BatchCollegeOption[]>([])
const years = ref<AcademicYearOption[]>([])
const batches = ref<BatchSummary[]>([])
const selectedCollegeId = ref<number | null>(null)
const selectedBatchId = ref<number | null>(null)
const detail = ref<BatchDetail | null>(null)
const rosterRound = ref<number | null>(null)
watch(selectedBatchId, () => { rosterRound.value = null })
const scheduleEditor = ref<HTMLFormElement | null>(null)
const quotaEditor = ref<HTMLElement | null>(null)
const scheduleEditorOpen = ref(false)
const scheduleEditorOpening = ref(false)
const quotas = ref<TeacherQuota[]>([])
const batchMajors = ref<BatchMajor[]>([])
const statistics = ref<BatchStatistics | null>(null)
const quotaDrafts = reactive<Record<number, string>>({})
const schedule = reactive<Record<string, { start: string; end: string }>>({})
const createForm = reactive({ academicYearId: '', batchCode: '', name: '', supplementPlanned: false, appendReason: '' })
const editForm = reactive({ name: '', appendReason: '', scheduleReason: '' })
const roundExtendNo = ref<number | null>(null)
const roundNewEnd = ref('')
const roundExtendReason = ref('')
const roundReopenNo = ref<number | null>(null)
const roundReopenEnd = ref('')
const roundReopenReason = ref('')
const loading = ref(false)
const directoryLoading = ref(false)
const saving = ref(false)
const lifecycleSaving = ref(false)
const lifecycleAction = ref<BatchLifecycleAction | null>(null)
const errorMessage = ref('')
const successMessage = ref('')
let collegeDataRequestId = 0
let selectedBatchRequestId = 0
let activeLoadCount = 0

type BatchLifecycleAction = 'publish' | 'unpublish' | 'start' | 'pause' | 'resume' | 'cancel' | 'archive' | 'unarchive'

function beginLoading(): () => void {
  activeLoadCount += 1
  loading.value = true
  let finished = false
  return () => {
    if (finished) return
    finished = true
    activeLoadCount = Math.max(0, activeLoadCount - 1)
    loading.value = activeLoadCount > 0
  }
}

/** API 的 Long ID 在不同 JSON 配置下可能以数字或字符串传输，比较时统一按文本处理。 */
function sameBatchId(left: number | null | undefined, right: number | null | undefined): boolean {
  return left !== null && left !== undefined && right !== null && right !== undefined && String(left) === String(right)
}

// 这些计算值只控制页面展示；接口授权、学院/批次范围和状态仍由后端逐请求验证。
const selectedCollege = computed(() => colleges.value.find((item) => item.id === selectedCollegeId.value) ?? null)
const selectedBatch = computed(() => detail.value?.batch ?? null)
const selectedBatchOption = computed(() => batches.value.find((item) => sameBatchId(item.id, selectedBatchId.value)) ?? null)
const stageOrder = ['FILLING', 'ROUND_1', 'ROUND_2', 'ROUND_3', 'SUPPLEMENT']
const visibleStages = computed(() => stageOrder.filter((code) => code !== 'SUPPLEMENT' || selectedBatch.value?.supplementPlanned))
const stageNames: Record<string, string> = {
  FILLING: '学生填报', ROUND_1: '第一轮处理', ROUND_2: '第二轮处理', ROUND_3: '第三轮处理', SUPPLEMENT: '补选窗口',
}
const currentTime = ref(Date.now())
const timeRefresh = window.setInterval(() => { currentTime.value = Date.now() }, 1000)
onUnmounted(() => window.clearInterval(timeRefresh))
const earliestNewTime = computed(() => toLocalInput(new Date(Math.ceil(currentTime.value / 60000) * 60000).toISOString()))
const scheduleErrors = computed(() => {
  const errors: Record<string, { start?: string; end?: string }> = {}
  visibleStages.value.forEach((stage, index) => {
    const current = schedule[stage]
    const previousStage = visibleStages.value[index - 1]
    const previous = previousStage ? schedule[previousStage] : undefined
    const start = current?.start ? new Date(`${current.start}+08:00`).getTime() : null
    const end = current?.end ? new Date(`${current.end}+08:00`).getTime() : null
    const previousEnd = previous?.end ? new Date(`${previous.end}+08:00`).getTime() : null
    const stored = detail.value?.stages.find((item) => item.stageCode === stage)
    const error: { start?: string; end?: string } = {}
    if (selectedBatch.value?.status === 'DRAFT') {
      if (start !== null && start < currentTime.value) error.start = '开始时间不能早于当前时间，请选择未来时间。'
      if (end !== null && end < currentTime.value) error.end = '结束时间不能早于当前时间。'
    }
    if (['ACTIVE', 'PAUSED'].includes(selectedBatch.value?.status ?? '') && stored) {
      const startChanged = current?.start !== toLocalInput(stored.plannedStartAt)
      const endChanged = current?.end !== toLocalInput(stored.plannedEndAt)
      if (stored.status !== 'OPEN' && stored.status !== 'CLOSED' && startChanged && start !== null && start < currentTime.value) {
        error.start = '未开始阶段的新开始时间不能早于当前时间。'
      }
      if (stored.status === 'OPEN' && endChanged && end !== null) {
        if (selectedBatch.value?.status === 'PAUSED' && end <= currentTime.value) {
          error.end = '暂停期间调整的当前阶段结束时间必须晚于当前时间。'
        } else if (selectedBatch.value?.status === 'ACTIVE' && stored.plannedEndAt &&
          (end <= currentTime.value || new Date(stored.plannedEndAt).getTime() <= currentTime.value || end <= new Date(stored.plannedEndAt).getTime())) {
          error.end = '运行中的当前阶段只能延长结束时间。'
        }
      }
    }
    if (start !== null && !Number.isFinite(start)) error.start = '请填写有效的开始时间。'
    if (end !== null && !Number.isFinite(end)) error.end = '请填写有效的结束时间。'
    if (start !== null && end !== null && end <= start) error.end = '结束时间必须晚于开始时间，不能相等。'
    if (start !== null && previousEnd !== null && start < previousEnd) {
      error.start = `开始时间不能早于${stageNames[previousStage]}的结束时间。`
    }
    errors[stage] = error
  })
  return errors
})
const hasScheduleErrors = computed(() => Object.values(scheduleErrors.value).some((error) => error.start || error.end))
const scheduleComplete = computed(() => visibleStages.value.every((stage) => schedule[stage]?.start && schedule[stage]?.end))
const scheduleSaved = computed(() => visibleStages.value.every((code) => {
  const stored = detail.value?.stages.find((stage) => stage.stageCode === code)
  return stored && schedule[code]?.start === toLocalInput(stored.plannedStartAt)
    && schedule[code]?.end === toLocalInput(stored.plannedEndAt)
}))
function minimumStageTime(stage: string, field: 'start' | 'end'): string | undefined {
  if (selectedBatch.value?.status !== 'DRAFT') return undefined
  const index = visibleStages.value.indexOf(stage)
  const previousEnd = schedule[visibleStages.value[index - 1]]?.end
  const ownStart = schedule[stage]?.start
  const related = field === 'start' ? previousEnd : ownStart
  if (!related) return earliestNewTime.value
  const milliseconds = new Date(`${related}+08:00`).getTime() + (field === 'end' ? 60000 : 0)
  if (!Number.isFinite(milliseconds)) return earliestNewTime.value
  const relatedMinimum = toLocalInput(new Date(milliseconds).toISOString())
  return relatedMinimum > earliestNewTime.value ? relatedMinimum : earliestNewTime.value
}
const statusNames: Record<string, string> = {
  DRAFT: '草稿', SCHEDULED: '已发布', ACTIVE: '运行中', PAUSED: '已暂停', COMPLETED: '已完成', ARCHIVED: '已归档', CANCELLED: '已取消',
}
const isEditable = computed(() => props.canManage && ['DRAFT', 'SCHEDULED', 'ACTIVE', 'PAUSED'].includes(selectedBatch.value?.status ?? ''))
function canEditScheduleField(stageCode: string, field: 'start' | 'end'): boolean {
  if (!isEditable.value) return false
  if (['DRAFT', 'SCHEDULED'].includes(selectedBatch.value?.status ?? '')) return true
  const stageStatus = detail.value?.stages.find((stage) => stage.stageCode === stageCode)?.status
  if (stageStatus === 'CLOSED') return false
  if (stageStatus === 'OPEN') return field === 'end'
  return true
}
const canCreate = computed(() => props.canManage && selectedCollege.value?.canCreateBatch === true)
const scheduleReady = computed(() => scheduleComplete.value && !hasScheduleErrors.value && scheduleSaved.value)
const quotaReady = computed(() => quotas.value.some((row) => row.quotaLimit !== null))
const majorsReady = computed(() => batchMajors.value.length > 0)
const appendReasonRequired = computed(() => {
  const academicYearId = Number(createForm.academicYearId)
  return selectedCollegeId.value !== null && Number.isSafeInteger(academicYearId)
    && batches.value.some((batch) => batch.collegeId === selectedCollegeId.value
      && batch.academicYearId === academicYearId)
})
const openRounds = computed(() => statistics.value?.rounds.filter((round) => round.stageStatus === 'OPEN') ?? [])
const reopenableRounds = computed(() => (detail.value?.stages ?? []).flatMap((stage) => {
  const match = /^ROUND_([1-3])$/.exec(stage.stageCode)
  if (!match || stage.status !== 'CLOSED' || stage.closeReason !== `ROUND_${match[1]}_DEADLINE`) return []
  return [{ roundNo: Number(match[1]) }]
}))

function friendlyError(error: unknown): string {
  if (error instanceof ApiError) return error.message
  return '暂时无法连接服务，请稍后重试。'
}

/** 将 API UTC 时间转为上海本地 datetime-local 字段；该输入控件本身不携带时区。 */
function toLocalInput(value: string | null): string {
  if (!value) return ''
  return new Intl.DateTimeFormat('sv-SE', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }).format(new Date(value)).replace(' ', 'T')
}

/** datetime-local 约定按上海时区解释，再序列化为带 Z 的 UTC 时间交给 API。 */
function toUtc(value: string): string { return new Date(`${value}:00+08:00`).toISOString() }

function focusScheduleEditor(): void {
  scheduleEditor.value?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  scheduleEditor.value?.querySelector<HTMLInputElement>('input:not([disabled])')?.focus({ preventScroll: true })
}

function focusQuotaEditor(): void {
  quotaEditor.value?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  quotaEditor.value?.querySelector<HTMLInputElement>('input:not([disabled])')?.focus({ preventScroll: true })
}

async function toggleScheduleEditor(): Promise<void> {
  const batch = selectedBatchOption.value
  if (!batch || !['SCHEDULED', 'ACTIVE', 'PAUSED'].includes(batch.status)) return
  if (scheduleEditorOpen.value) {
    scheduleEditorOpen.value = false
    return
  }
  if (scheduleEditorOpening.value) return
  const batchId = batch.id
  scheduleEditorOpening.value = true
  try {
    if (!sameBatchId(selectedBatch.value?.id, batchId)) await loadSelectedBatch()
    if (!sameBatchId(selectedBatchId.value, batchId) || !sameBatchId(selectedBatch.value?.id, batchId)) return
    scheduleEditorOpen.value = true
    await nextTick()
    focusScheduleEditor()
  } finally { scheduleEditorOpening.value = false }
}

/** 首次加载授权学院，并默认选中第一个可访问项。 */
async function loadColleges(): Promise<void> {
  const finishLoading = beginLoading()
  try {
    colleges.value = await selectionBatchService.colleges()
    if (!selectedCollegeId.value && colleges.value.length) selectedCollegeId.value = colleges.value[0].id
  } catch (error) { errorMessage.value = friendlyError(error) }
  finally { finishLoading() }
}

/** 学院选择改变后并行加载学年和批次；若旧批次不再属于当前学院则切换到第一项。 */
async function loadCollegeData(): Promise<void> {
  const requestId = ++collegeDataRequestId
  const collegeId = selectedCollegeId.value
  if (!collegeId) {
    directoryLoading.value = false
    years.value = []; batches.value = []
    return
  }
  directoryLoading.value = true
  const finishLoading = beginLoading()
  errorMessage.value = ''
  try {
    const [nextYears, nextBatches] = await Promise.all([
      selectionBatchService.academicYears(collegeId),
      selectionBatchService.batches(collegeId),
    ])
    if (requestId !== collegeDataRequestId || selectedCollegeId.value !== collegeId) return
    years.value = nextYears
    batches.value = nextBatches
    if (!createForm.academicYearId && nextYears.length) createForm.academicYearId = String(nextYears[0].id)
    if (!nextBatches.some((item) => sameBatchId(item.id, selectedBatchId.value))) {
      selectedBatchId.value = nextBatches[0]?.id ?? null
    }
    if (!selectedBatchId.value) { detail.value = null; quotas.value = []; batchMajors.value = []; statistics.value = null }
  } catch (error) {
    if (requestId === collegeDataRequestId) errorMessage.value = friendlyError(error)
  } finally {
    if (requestId === collegeDataRequestId) directoryLoading.value = false
    finishLoading()
  }
}

/** 批次选择改变后并行取详情和名额表，并把服务端数据映射到可编辑表单。 */
async function loadSelectedBatch(): Promise<BatchDetail | null> {
  const requestId = ++selectedBatchRequestId
  const batchId = selectedBatchId.value
  if (!batchId) {
    detail.value = null; quotas.value = []; batchMajors.value = []; statistics.value = null; editForm.scheduleReason = ''
    return null
  }
  const finishLoading = beginLoading()
  errorMessage.value = ''
  // 刷新同一批次时保留已加载详情，避免时间编辑器在请求期间消失。
  // 只有切换到另一批次时才清空旧批次数据。
  if (!sameBatchId(detail.value?.batch.id, batchId)) {
    detail.value = null; quotas.value = []; batchMajors.value = []; statistics.value = null; editForm.scheduleReason = ''
  }
  try {
    const loaded = await selectionBatchService.batch(batchId)
    if (requestId !== selectedBatchRequestId || !sameBatchId(selectedBatchId.value, batchId)) return null
    applyBatchDetail(loaded)
    editForm.name = loaded.batch.name
    editForm.appendReason = loaded.batch.appendReason ?? ''
    for (const stage of loaded.stages) {
      schedule[stage.stageCode] = { start: toLocalInput(stage.plannedStartAt), end: toLocalInput(stage.plannedEndAt) }
    }

    // 详情决定生命周期视图；名额、统计或专业目录读失败时保留可用的排期/发布界面，并单独提示。
    const [quotaResult, statisticsResult, majorsResult] = await Promise.allSettled([
      selectionBatchService.quotas(batchId), selectionBatchService.statistics(batchId),
      selectionBatchService.majorsForBatch(batchId),
    ])
    if (requestId !== selectedBatchRequestId || !sameBatchId(selectedBatchId.value, batchId)) return null
    const loadWarnings: string[] = []
    if (quotaResult.status === 'fulfilled') {
      quotas.value = quotaResult.value
      for (const row of quotaResult.value) quotaDrafts[row.teacherId] = row.quotaLimit === null ? '' : String(row.quotaLimit)
    } else loadWarnings.push('导师名额')
    if (statisticsResult.status === 'fulfilled') statistics.value = statisticsResult.value
    else loadWarnings.push('统计')
    if (majorsResult.status === 'fulfilled') batchMajors.value = majorsResult.value
    else loadWarnings.push('专业目录')
    if (!openRounds.value.some((round) => round.roundNo === roundExtendNo.value)) roundExtendNo.value = openRounds.value[0]?.roundNo ?? null
    if (!reopenableRounds.value.some((round) => round.roundNo === roundReopenNo.value)) roundReopenNo.value = reopenableRounds.value[0]?.roundNo ?? null
    if (loadWarnings.length) errorMessage.value = `批次详情已加载，但${loadWarnings.join('、')}读取失败，请稍后重试。`
    return loaded
  } catch (error) {
    if (requestId === selectedBatchRequestId) errorMessage.value = friendlyError(error)
    return null
  } finally { finishLoading() }
}

/** 写接口返回的权威详情立即同步到详情区和批次选择器，避免等待手动刷新。 */
function applyBatchDetail(updated: BatchDetail): void {
  if (!sameBatchId(selectedBatchId.value, updated.batch.id)) return
  detail.value = updated
  batches.value = batches.value.map((batch) => sameBatchId(batch.id, updated.batch.id)
    ? { ...batch, ...updated.batch }
    : batch)
}

async function createBatch(): Promise<void> {
  if (saving.value || directoryLoading.value || !selectedCollegeId.value || !createForm.academicYearId) return
  if (!years.value.some((year) => String(year.id) === createForm.academicYearId)) {
    errorMessage.value = '请先选择有效学年。'
    successMessage.value = ''
    return
  }
  if (appendReasonRequired.value && !createForm.appendReason.trim()) {
    errorMessage.value = '同一学院和学年已有批次，请填写追加批次原因。'
    successMessage.value = ''
    return
  }
  saving.value = true; errorMessage.value = ''; successMessage.value = ''
  const previousBatchIds = new Set(batches.value.map((batch) => String(batch.id)))
  const collegeId = selectedCollegeId.value
  const academicYearId = Number(createForm.academicYearId)
  const batchCode = createForm.batchCode.trim()
  try {
    const created = await selectionBatchService.create({
      collegeId, academicYearId,
      batchCode, name: createForm.name.trim(),
      supplementPlanned: createForm.supplementPlanned, appendReason: createForm.appendReason.trim() || null,
    })
    selectedBatchId.value = created.batch.id
    applyBatchDetail(created)
    if (!batches.value.some((batch) => sameBatchId(batch.id, created.batch.id))) batches.value = [...batches.value, created.batch]
    createForm.batchCode = ''; createForm.name = ''; createForm.appendReason = ''; createForm.supplementPlanned = false
    await loadCollegeData()
    selectedBatchId.value = created.batch.id
    await loadSelectedBatch()
    successMessage.value = '批次草稿已创建。'
    await nextTick()
    focusScheduleEditor()
  } catch (error) {
    const requestError = friendlyError(error)
    // 创建响应可能在数据库提交后中断；用学院/学年/编号回读确认新记录，避免要求管理员刷新页面。
    const definitiveClientError = error instanceof ApiError && error.status >= 400 && error.status < 500
    if (!definitiveClientError) {
      try {
        if (collegeId !== null) {
          const freshBatches = await selectionBatchService.batches(collegeId)
          batches.value = freshBatches
          const recovered = freshBatches.find((batch) => batch.academicYearId === academicYearId
            && batch.batchCode === batchCode && !previousBatchIds.has(String(batch.id)))
          if (recovered) {
            selectedBatchId.value = recovered.id
            await loadSelectedBatch()
            successMessage.value = '批次草稿已创建。'
            await nextTick()
            focusScheduleEditor()
            return
          }
        }
      } catch {
        // 列表回读也失败时保留创建请求的原始错误。
      }
    }
    errorMessage.value = requestError
  }
  finally { saving.value = false }
}

/** 元数据更新使用当前 ETag 版本；冲突或失败时重读服务端值，避免保留过期表单。 */
async function saveMetadata(): Promise<void> {
  if (!selectedBatch.value) return
  saving.value = true; errorMessage.value = ''; successMessage.value = ''
  try {
    const updated = await selectionBatchService.update(selectedBatch.value.id, selectedBatch.value.rowVersion, {
      name: editForm.name.trim(), appendReason: editForm.appendReason.trim() || null,
    })
    applyBatchDetail(updated)
    successMessage.value = '批次信息已保存。'
  } catch (error) { errorMessage.value = friendlyError(error); await loadSelectedBatch() }
  finally { saving.value = false }
}

/**
 * 一次提交当前批次所有必需阶段，保持完整排期校验所需的上下文。
 * 每个 datetime-local 字段先转换成 UTC，服务端再执行顺序、重叠和生命周期边界校验。
 */
async function saveSchedule(): Promise<void> {
  if (!selectedBatch.value) return
  const batchId = selectedBatch.value.id
  const reason = editForm.scheduleReason.trim()
  if (selectedBatch.value.status !== 'DRAFT' && !reason) {
    errorMessage.value = '调整已发布批次的时间必须填写原因。'
    successMessage.value = ''
    return
  }
  if (hasScheduleErrors.value) {
    errorMessage.value = '请先修正阶段时间旁的提示，再保存排期。'
    successMessage.value = ''
    return
  }
  if (visibleStages.value.some((stage) => !schedule[stage]?.start || !schedule[stage]?.end)) {
    errorMessage.value = '请补全所有阶段的开始时间和结束时间。'
    successMessage.value = ''
    return
  }
  const stages = visibleStages.value.map((stageCode) => ({
    stageCode,
    plannedStartAt: toUtc(schedule[stageCode]?.start ?? ''),
    plannedEndAt: toUtc(schedule[stageCode]?.end ?? ''),
  }))
  saving.value = true; errorMessage.value = ''; successMessage.value = ''
  try {
    const updated = await selectionBatchService.saveSchedule(batchId, selectedBatch.value.rowVersion, {
      stages, reason: reason || '批次排期配置',
    })
    applyBatchDetail(updated)
    editForm.scheduleReason = ''
    successMessage.value = '阶段时间已保存。'
  } catch (error) {
    const requestError = friendlyError(error)
    // 保存响应可能在数据库提交后中断。只读取批次详情来核对结果，避免整页名额/统计请求
    // 覆盖原错误，也避免失败时丢掉管理员刚填写的排期。
    try {
      const latest = await selectionBatchService.batch(batchId)
      if (sameBatchId(selectedBatchId.value, batchId)) {
        applyBatchDetail(latest)
        const saved = stages.every((stage) => {
          const current = latest.stages.find((item) => item.stageCode === stage.stageCode)
          return current?.plannedStartAt && current.plannedEndAt
            && Date.parse(current.plannedStartAt) === Date.parse(stage.plannedStartAt)
            && Date.parse(current.plannedEndAt) === Date.parse(stage.plannedEndAt)
        })
        if (saved) {
          editForm.scheduleReason = ''
          errorMessage.value = ''
          successMessage.value = '阶段时间已保存。'
          return
        }
      }
    } catch {
      // 保留原请求的错误；网络中断时二次读取也可能暂时不可用。
    }
    errorMessage.value = requestError
  }
  finally { saving.value = false }
}

/** 生命周期命令先应用写响应，再回读目录/详情/名额/统计，兼容响应中断与页面缓存。 */
async function runLifecycle(action: BatchLifecycleAction): Promise<void> {
  if (lifecycleSaving.value) return
  const lifecycleBatch = selectedBatch.value ?? selectedBatchOption.value
  if (!lifecycleBatch) return
  if (action === 'publish') {
    if (!scheduleReady.value) {
      errorMessage.value = '请先配置所有未来阶段时间并保存排期，再发布批次。'
      successMessage.value = ''
      await nextTick()
      focusScheduleEditor()
      return
    }
    if (!quotaReady.value) {
      errorMessage.value = '请先为至少一位符合资格的导师设置名额，再发布批次。'
      successMessage.value = ''
      await nextTick()
      focusQuotaEditor()
      return
    }
    if (!majorsReady.value) {
      errorMessage.value = '学院专业目录尚未加载成功或没有启用专业，无法发布批次。请检查目录后重试。'
      successMessage.value = ''
      return
    }
  }
  const confirmations: Partial<Record<typeof action, string>> = {
    unpublish: '撤回后会释放该学院和学年的运行名额，批次回到草稿状态，可修改排期后再次发布。确定撤回吗？',
    pause: '暂停后，学生和导师暂时不能办理；志愿、申请、已锁定关系和已占用名额都会保留。确定暂停吗？',
    cancel: '取消后将停止本批次后续流程，撤销本批次全部已锁定关系并释放对应名额；待处理申请会取消。关系和操作历史保留。确定继续吗？',
    archive: '归档后批次进入只读历史状态。确定归档吗？',
    unarchive: '解除归档后批次回到已完成状态，供授权管理员进行审计纠错。确定继续吗？',
  }
  if (confirmations[action] && !window.confirm(confirmations[action])) return
  lifecycleSaving.value = true; lifecycleAction.value = action
  errorMessage.value = ''; successMessage.value = ''
  const collegeId = selectedCollegeId.value
  let operationError: unknown = null
  let updatedDetail: BatchDetail | null = null
  const expectedStatus: Record<BatchLifecycleAction, string> = {
    publish: 'SCHEDULED', unpublish: 'DRAFT', start: 'ACTIVE', pause: 'PAUSED',
    resume: 'ACTIVE', cancel: 'CANCELLED', archive: 'ARCHIVED', unarchive: 'COMPLETED',
  }
  const successMessages: Record<typeof action, string> = {
    publish: '批次已发布。', unpublish: '发布已撤回，批次回到草稿状态。', start: '批次已启动。', pause: '批次已暂停；志愿、申请、关系和名额均已保留。', resume: '批次已恢复，后续阶段已顺延。',
    cancel: '批次已取消，已锁定关系已撤销并释放导师名额；关系和操作历史已保留。', archive: '批次已归档。', unarchive: '批次已解除归档。',
  }
  try {
    try {
      const updated = action === 'publish'
        ? await selectionBatchService.publish(lifecycleBatch.id)
        : action === 'unpublish'
          ? await selectionBatchService.unpublish(lifecycleBatch.id)
        : action === 'start'
          ? await selectionBatchService.start(lifecycleBatch.id)
          : await selectionBatchService.lifecycle(lifecycleBatch.id, action)
      updatedDetail = updated
      applyBatchDetail(updated)
      successMessage.value = successMessages[action]
    } catch (error) {
      operationError = error
      errorMessage.value = friendlyError(error)
    }

    // 任何生命周期状态变化后都读取最新目录与详情；启动/取消同时刷新冻结范围、名额和统计。
    let refreshedSummary: BatchSummary | undefined
    let refreshedDetail: BatchDetail | null = null
    let refreshErrorMessage = ''
    try {
      if (collegeId !== null) {
        const freshBatches = await selectionBatchService.batches(collegeId)
        if (selectedCollegeId.value === collegeId) {
          batches.value = freshBatches
          refreshedSummary = freshBatches.find((batch) => sameBatchId(batch.id, lifecycleBatch.id))
        }
      }
      if (sameBatchId(selectedBatchId.value, lifecycleBatch.id)) {
        refreshedDetail = await loadSelectedBatch()
        if (refreshedDetail && errorMessage.value) refreshErrorMessage = errorMessage.value
        if (!refreshedDetail) refreshErrorMessage = errorMessage.value || '批次详情未能刷新'
        if (!refreshedDetail && refreshedSummary && detail.value) {
          detail.value = { ...detail.value, batch: refreshedSummary }
        }
      }
    } catch (error) {
      refreshErrorMessage = friendlyError(error)
    }

    const actualStatus = refreshedDetail?.batch.status ?? refreshedSummary?.status ?? updatedDetail?.batch.status
    if (actualStatus === expectedStatus[action]) {
      errorMessage.value = refreshErrorMessage
        ? `批次状态已更新，但详情、名额或统计未能完整刷新：${refreshErrorMessage}`
        : ''
      successMessage.value = successMessages[action]
      if (action === 'unpublish' && sameBatchId(selectedBatchId.value, lifecycleBatch.id)) {
        await nextTick()
        focusScheduleEditor()
      }
    } else if (operationError) {
      errorMessage.value = friendlyError(operationError)
      successMessage.value = ''
    } else if (updatedDetail) {
      successMessage.value = successMessages[action]
      if (refreshErrorMessage) errorMessage.value = `批次操作已完成，但界面刷新失败：${refreshErrorMessage}`
    }
  } finally {
    lifecycleSaving.value = false
    lifecycleAction.value = null
  }
}

/** 名额数先做即时整数检查；最终资格、If-Match 版本和已占用下限由后端权威校验。 */
async function saveQuota(row: TeacherQuota): Promise<void> {
  if (!selectedBatch.value) return
  const limit = Number(quotaDrafts[row.teacherId])
  if (!Number.isInteger(limit) || limit < 0) { errorMessage.value = '名额须为大于等于 0 的整数。'; return }
  saving.value = true; errorMessage.value = ''; successMessage.value = ''
  try {
    await selectionBatchService.setQuota(selectedBatch.value.id, row, limit)
    const [updatedQuotas, updatedStatistics] = await Promise.all([
      selectionBatchService.quotas(selectedBatch.value.id),
      selectionBatchService.statistics(selectedBatch.value.id),
    ])
    quotas.value = updatedQuotas
    statistics.value = updatedStatistics
    successMessage.value = `${row.fullName} 的名额已保存。`
  } catch (error) { errorMessage.value = friendlyError(error); await loadSelectedBatch() }
  finally { saving.value = false }
}

async function extendRound(): Promise<void> {
  if (!selectedBatch.value || !roundExtendNo.value || !roundNewEnd.value) return
  if (!roundExtendReason.value.trim()) { errorMessage.value = '请填写延期原因。'; return }
  saving.value = true; errorMessage.value = ''; successMessage.value = ''
  try {
    const updated = await selectionBatchService.extendRound(selectedBatch.value.id, roundExtendNo.value,
      selectedBatch.value.rowVersion, toUtc(roundNewEnd.value), roundExtendReason.value.trim())
    applyBatchDetail(updated)
    statistics.value = await selectionBatchService.statistics(updated.batch.id)
    roundNewEnd.value = ''; roundExtendReason.value = ''
    successMessage.value = `第 ${roundExtendNo.value} 轮截止时间已延长，后续阶段已顺延。`
  } catch (error) { errorMessage.value = friendlyError(error); await loadSelectedBatch() }
  finally { saving.value = false }
}

async function reopenRound(): Promise<void> {
  if (!selectedBatch.value || !roundReopenNo.value || !roundReopenEnd.value) return
  if (!roundReopenReason.value.trim()) { errorMessage.value = '请填写重开原因。'; return }
  saving.value = true; errorMessage.value = ''; successMessage.value = ''
  try {
    const updated = await selectionBatchService.reopenRound(selectedBatch.value.id, roundReopenNo.value,
      selectedBatch.value.rowVersion, toUtc(roundReopenEnd.value), roundReopenReason.value.trim())
    applyBatchDetail(updated)
    statistics.value = await selectionBatchService.statistics(updated.batch.id)
    roundReopenEnd.value = ''; roundReopenReason.value = ''
    successMessage.value = `第 ${roundReopenNo.value} 轮已重新开放；系统自动结案申请已恢复，后续阶段按新截止时间顺延。`
  } catch (error) { errorMessage.value = friendlyError(error); await loadSelectedBatch() }
  finally { saving.value = false }
}

// 依赖变化时自动刷新对应层级，避免切换学院后沿用上一个学院的批次详情。
watch(selectedCollegeId, loadCollegeData)
watch(selectedBatchId, () => {
  scheduleEditorOpen.value = false
  void loadSelectedBatch()
})
onMounted(loadColleges)
</script>

<template>
  <section class="batch-panel" aria-labelledby="batch-panel-title">
    <div class="batch-panel-heading">
      <div>
        <p class="batch-eyebrow">BATCH SETUP</p>
        <h3 id="batch-panel-title">批次管理</h3>
        <p>配置招生窗口、导师名额并发布批次。学生名单和导师范围在填报开始时冻结。</p>
      </div>
      <span class="batch-panel-stamp">业务配置</span>
    </div>

    <p v-if="errorMessage" class="form-error" role="alert">{{ errorMessage }}</p>
    <p v-if="successMessage" class="form-success" role="status">{{ successMessage }}</p>

    <!-- 当前批次目录受 BATCH_MANAGER 授权范围过滤，批次级授权只会看到获授项。 -->
    <div class="batch-toolbar">
      <label>管理学院
        <select v-model.number="selectedCollegeId" :disabled="loading || !colleges.length">
          <option v-for="college in colleges" :key="college.id" :value="college.id">{{ college.name }}（{{ college.code }}）</option>
        </select>
      </label>
      <label>现有批次
        <select v-model.number="selectedBatchId" :disabled="loading || !batches.length">
          <option :value="null">{{ batches.length ? '选择批次' : '暂无批次' }}</option>
          <option v-for="batch in batches" :key="batch.id" :value="batch.id">
            {{ batch.yearCode }} · {{ batch.name }}（{{ statusNames[batch.status] ?? batch.status }}）
          </option>
        </select>
      </label>
    </div>

    <div v-if="props.canManage && selectedBatchOption && ['DRAFT', 'SCHEDULED', 'ACTIVE', 'PAUSED'].includes(selectedBatchOption.status)" class="batch-lifecycle-actions">
      <div class="batch-lifecycle-copy">
        <span>批次操作</span>
        <strong>{{ selectedBatchOption.name }}</strong>
        <span class="batch-status-pill" :class="'batch-status-' + selectedBatchOption.status.toLowerCase()">{{ statusNames[selectedBatchOption.status] ?? selectedBatchOption.status }}</span>
        <small v-if="selectedBatchOption.status === 'DRAFT'">先配置并保存完整排期、导师名额，再发布；发布后需手动启动。</small>
      </div>
      <div class="batch-action-row">
        <button v-if="selectedBatchOption.status === 'DRAFT'" class="secondary-button" type="button" :disabled="!sameBatchId(selectedBatch?.id, selectedBatchOption.id)" @click="focusScheduleEditor">配置排期与名额</button>
        <button v-if="selectedBatchOption.status === 'DRAFT'" class="primary-button" type="button" :disabled="lifecycleSaving || !sameBatchId(selectedBatch?.id, selectedBatchOption.id)" @click="runLifecycle('publish')">{{ lifecycleSaving && lifecycleAction === 'publish' ? '正在发布…' : '发布批次' }}</button>
        <button v-if="selectedBatchOption.status === 'SCHEDULED'" class="primary-button" type="button" :disabled="lifecycleSaving" @click="runLifecycle('start')">{{ lifecycleSaving && lifecycleAction === 'start' ? '正在启动…' : '启动批次' }}</button>
        <button v-if="selectedBatchOption.status === 'SCHEDULED'" class="secondary-button" type="button" :disabled="lifecycleSaving" @click="runLifecycle('unpublish')">{{ lifecycleSaving && lifecycleAction === 'unpublish' ? '正在撤回…' : '撤回发布' }}</button>
        <button v-if="props.canManage && selectedBatchOption && ['SCHEDULED', 'ACTIVE', 'PAUSED'].includes(selectedBatchOption.status)" class="secondary-button" type="button" :disabled="saving || scheduleEditorOpening" @click="toggleScheduleEditor">{{ scheduleEditorOpening ? '正在载入排期…' : scheduleEditorOpen ? '收起时间调整' : '调整批次时间' }}</button>
        <button v-if="selectedBatchOption.status === 'ACTIVE'" class="secondary-button" type="button" :disabled="lifecycleSaving" @click="runLifecycle('pause')">{{ lifecycleSaving && lifecycleAction === 'pause' ? '正在暂停…' : '暂停批次' }}</button>
        <button v-if="selectedBatchOption.status === 'PAUSED'" class="primary-button" type="button" :disabled="lifecycleSaving" @click="runLifecycle('resume')">{{ lifecycleSaving && lifecycleAction === 'resume' ? '正在恢复…' : '恢复批次' }}</button>
        <button v-if="['DRAFT', 'SCHEDULED', 'ACTIVE', 'PAUSED'].includes(selectedBatchOption.status)" class="text-button" type="button" :disabled="lifecycleSaving" @click="runLifecycle('cancel')">{{ lifecycleSaving && lifecycleAction === 'cancel' ? '正在取消…' : '取消批次' }}</button>
      </div>
    </div>

    <template v-if="selectedBatch">
      <div class="batch-selected-summary">
        <div><span class="batch-summary-label">当前批次</span><strong>{{ selectedBatch.name }}</strong><small>{{ selectedBatch.yearCode }} · {{ selectedBatch.batchCode }} · {{ selectedBatch.collegeName }}</small></div>
        <span class="batch-status-pill" :class="`batch-status-${selectedBatch.status.toLowerCase()}`">{{ statusNames[selectedBatch.status] ?? selectedBatch.status }}</span>
      </div>

      <div class="batch-phase-strip" aria-label="批次阶段">
        <div v-for="(stage, index) in visibleStages" :key="stage" class="batch-phase" :class="{ 'phase-current': detail?.stages.find((item) => item.stageCode === stage)?.status === 'OPEN' }">
          <span>{{ String(index + 1).padStart(2, '0') }}</span><strong>{{ stageNames[stage] }}</strong>
        </div>
      </div>

      <form v-if="props.canManage && selectedBatch.status === 'DRAFT'" class="batch-inline-form" @submit.prevent="saveMetadata">
        <label>批次名称<input v-model="editForm.name" maxlength="128" required /></label>
        <label>追加批次原因<textarea v-model="editForm.appendReason" rows="2" maxlength="4000" /></label>
        <button class="text-button" type="submit" :disabled="saving">保存基本信息</button>
      </form>

      <!-- 发布前必须配置完整阶段；发布后服务端仅允许规则限定的时间调整。 -->
      <form v-if="!props.canManage || !['SCHEDULED', 'ACTIVE', 'PAUSED'].includes(selectedBatch.status) || scheduleEditorOpen" ref="scheduleEditor" class="batch-schedule-form" @submit.prevent="saveSchedule">
        <div class="batch-subheading"><span>{{ ['ACTIVE', 'PAUSED'].includes(selectedBatch.status) ? '调整批次时间' : '阶段时间' }}</span><small>按填报、第一轮、第二轮、第三轮及补选的顺序设置</small></div>
        <p v-if="selectedBatch.status === 'DRAFT'" class="batch-time-help">请选择当前之后的时间；结束时间必须晚于开始时间。先保存有效排期，再发布批次。</p>
        <p v-else-if="['ACTIVE', 'PAUSED'].includes(selectedBatch.status)" class="batch-time-help">可调整尚未开始阶段的时间。当前开放阶段的开始时间和已关闭阶段不可修改；运行中只能延长当前阶段，暂停时可将当前阶段结束时间调整到未来，恢复时仍按暂停时长整体顺延。修改后请填写原因。</p>
        <p v-else-if="selectedBatch.status === 'SCHEDULED'" class="batch-time-help">批次尚未启动，可调整完整排期；修改后请填写原因。</p>
        <div v-if="selectedBatch.status === 'DRAFT'" class="batch-publish-checklist" aria-label="发布前检查">
          <strong>发布前检查</strong>
          <span :class="{ 'batch-check-ready': scheduleReady }">{{ scheduleReady ? '✓' : '○' }} 完整排期已保存</span>
          <span :class="{ 'batch-check-ready': quotaReady }">{{ quotaReady ? '✓' : '○' }} 至少一位有效导师已设置名额</span>
          <span :class="{ 'batch-check-ready': majorsReady }">{{ majorsReady ? '✓' : '○' }} 学院专业目录可用</span>
        </div>
        <div class="batch-schedule-head"><span>阶段</span><span>开始时间</span><span>结束时间</span></div>
        <div v-for="stage in visibleStages" :key="stage" class="batch-schedule-row">
          <strong>{{ stageNames[stage] }}</strong>
          <div class="batch-time-field">
            <input v-model="schedule[stage].start" type="datetime-local" :min="minimumStageTime(stage, 'start')" :disabled="!canEditScheduleField(stage, 'start') || saving" :aria-label="`${stageNames[stage]}开始时间`" :aria-invalid="Boolean(scheduleErrors[stage]?.start)" :aria-describedby="scheduleErrors[stage]?.start ? `schedule-${stage}-start-error` : undefined" required />
            <small v-if="scheduleErrors[stage]?.start" :id="`schedule-${stage}-start-error`" class="batch-time-error" aria-live="polite">{{ scheduleErrors[stage].start }}</small>
          </div>
          <div class="batch-time-field">
            <input v-model="schedule[stage].end" type="datetime-local" :min="minimumStageTime(stage, 'end')" :disabled="!canEditScheduleField(stage, 'end') || saving" :aria-label="`${stageNames[stage]}结束时间`" :aria-invalid="Boolean(scheduleErrors[stage]?.end)" :aria-describedby="scheduleErrors[stage]?.end ? `schedule-${stage}-end-error` : undefined" required />
            <small v-if="scheduleErrors[stage]?.end" :id="`schedule-${stage}-end-error`" class="batch-time-error" aria-live="polite">{{ scheduleErrors[stage].end }}</small>
          </div>
        </div>
        <label v-if="isEditable" class="batch-reason">排期变更原因<textarea v-model="editForm.scheduleReason" rows="2" maxlength="4000" :required="selectedBatch.status !== 'DRAFT'" :placeholder="selectedBatch.status === 'DRAFT' ? '初次配置可留空' : '请说明调整排期的原因'" /></label>
        <div class="batch-action-row">
          <button v-if="isEditable" class="secondary-button" type="submit" :disabled="saving || hasScheduleErrors || selectedBatch.status !== 'DRAFT' && !editForm.scheduleReason.trim()">{{ ['ACTIVE', 'PAUSED'].includes(selectedBatch.status) ? '保存时间调整' : '保存完整排期' }}</button>
          <button v-if="props.canManage && selectedBatch.status === 'COMPLETED'" class="secondary-button" type="button" :disabled="saving" @click="runLifecycle('archive')">归档批次</button>
          <button v-if="props.canManage && selectedBatch.status === 'ARCHIVED'" class="secondary-button" type="button" :disabled="saving" @click="runLifecycle('unarchive')">解除归档</button>
        </div>
      </form>

      <form v-if="props.canManage && openRounds.length && ['ACTIVE', 'PAUSED'].includes(selectedBatch.status)" class="batch-quota-section round-extension-section" @submit.prevent="extendRound">
        <div class="batch-subheading"><span>常规轮次延期</span><small>仅可在该轮原截止前延长；后续阶段会按相同时间差顺延</small></div>
        <label>开放轮次<select v-model.number="roundExtendNo" required><option v-for="round in openRounds" :key="round.roundNo" :value="round.roundNo">第 {{ round.roundNo }} 轮</option></select></label>
        <label>新的截止时间<input v-model="roundNewEnd" type="datetime-local" required /></label>
        <label>延期原因<textarea v-model="roundExtendReason" rows="2" maxlength="4000" required /></label>
        <button class="secondary-button" type="submit" :disabled="saving">保存延期</button>
      </form>

      <form v-if="props.canManage && selectedBatch.status === 'PAUSED' && reopenableRounds.length" class="batch-quota-section round-extension-section round-reopen-section" @submit.prevent="reopenRound">
        <div class="batch-subheading"><span>重开已关闭轮次</span><small>重开后仍须恢复批次；导师已作出的决定和已锁定关系不会回滚</small></div>
        <label>关闭轮次<select v-model.number="roundReopenNo" required><option v-for="round in reopenableRounds" :key="round.roundNo" :value="round.roundNo">第 {{ round.roundNo }} 轮</option></select></label>
        <label>新的截止时间<input v-model="roundReopenEnd" type="datetime-local" required /></label>
        <label>重开原因<textarea v-model="roundReopenReason" rows="2" maxlength="4000" required /></label>
        <button class="secondary-button" type="submit" :disabled="saving">重开轮次</button>
      </form>

      <p v-if="!props.canManage" class="batch-readonly-note">当前是只读审计视图，可查看批次配置与统计；修改操作需要批次管理授权。</p>

      <div v-if="statistics" class="batch-statistics">
        <div class="batch-subheading"><span>批次统计</span><small>分母取填报窗口开放时冻结的合格名单</small></div>
        <div class="batch-stat-grid">
          <div><span>冻结分母</span><strong>{{ statistics.frozenDenominator ?? '尚未冻结' }}</strong></div>
          <div><span>已匹配</span><strong>{{ statistics.matchedCount }}</strong></div>
          <div><span>当前未匹配</span><strong>{{ statistics.currentUnmatchedCount }}</strong></div>
          <div><span>正常志愿未匹配</span><strong>{{ statistics.normalPreferenceUnmatchedCount }}</strong></div>
          <div><span>未提交志愿</span><strong>{{ statistics.notSubmittedCount }}</strong></div>
          <div><span>所有剩余志愿被跳过</span><strong>{{ statistics.allRemainingPreferencesSkippedCount }}</strong></div>
          <div><span>身份纠错后未匹配</span><strong>{{ statistics.identityCorrectionUnmatchedCount }}</strong></div>
          <div><span>关系撤销后未匹配</span><strong>{{ statistics.relationCorrectionUnmatchedCount }}</strong></div>
          <div><span>取消批次未匹配</span><strong>{{ statistics.batchCancelledUnmatchedCount }}</strong></div>
          <div><span>补选录取</span><strong>{{ statistics.supplementAdmittedCount }}</strong></div>
          <div><span>补选结束后仍未匹配</span><strong>{{ statistics.supplementStillUnmatchedCount ?? '尚未结案' }}</strong></div>
          <div><span>名额已用 / 总额</span><strong>{{ statistics.occupiedQuota }} / {{ statistics.quotaLimit }}</strong></div>
        </div>
        <div class="batch-round-table-wrap">
          <table class="batch-quota-table batch-round-table">
            <thead><tr><th>轮次</th><th>阶段</th><th>待处理</th><th>录取</th><th>不录取</th><th>时间跳过</th><th>批次取消</th><th v-if="props.canManage">未完成名单</th></tr></thead>
            <tbody>
              <tr v-for="round in statistics.rounds" :key="round.roundNo">
                <td>第 {{ round.roundNo }} 轮</td><td>{{ round.stageStatus }}</td><td>{{ round.pendingApplicationCount }}</td>
                <td>{{ round.admittedCount }}</td><td>{{ round.notAdmittedCount }}</td><td>{{ round.skippedStudentCount }}</td>
                <td>{{ round.cancelledApplicationCount }}</td>
                <td v-if="props.canManage"><button type="button" class="secondary-button" :disabled="round.stageStatus !== 'CLOSED' || selectedBatch?.status === 'CANCELLED'" @click="rosterRound = round.roundNo">未完成互选学生</button></td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- 名额表展示剩余量和导师范围状态；实际录取时仍由服务端在名额行锁下扣减。 -->
      <div ref="quotaEditor" class="batch-quota-section">
        <div class="batch-subheading"><span>导师名额</span><small>只列出本学年资格有效、账号启用且公开资料已发布的导师</small></div>
        <div v-if="!quotas.length" class="batch-empty">当前批次没有符合条件的导师记录。</div>
        <div v-else class="batch-quota-table-wrap">
          <table class="batch-quota-table">
            <thead><tr><th>导师</th><th>资格</th><th>名额上限</th><th>已占用</th><th>剩余</th><th>招生范围</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="row in quotas" :key="row.teacherId">
                <td><strong>{{ row.fullName }}</strong><small>{{ row.employeeNo }}</small></td>
                <td><span class="quota-eligible">有效</span></td>
                <td><input v-model="quotaDrafts[row.teacherId]" class="quota-input" type="number" min="0" step="1" :disabled="!isEditable || saving" :aria-label="`${row.fullName} 名额上限`" /></td>
                <td>{{ row.occupiedCount }}</td><td class="quota-remaining">{{ row.remainingCount }}</td>
                <td><span>{{ row.scopeFrozen ? '已冻结' : row.scopeConfigured ? '已配置' : '默认全选' }}</span></td>
                <td><button class="quota-save-button" type="button" :disabled="!isEditable || saving" @click="saveQuota(row)">保存名额</button></td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </template>

    <!-- 新建入口放在所选批次的配置之后，避免把草稿的排期和发布操作挤出首屏。 -->
    <form v-if="canCreate" class="batch-create-form" @submit.prevent="createBatch">
      <div class="batch-subheading"><span>新建草稿</span><small>学院和学年决定批次的业务范围</small></div>
      <label>学年
        <select v-model="createForm.academicYearId" required>
          <option value="" disabled>选择学年</option>
          <option v-for="year in years" :key="year.id" :value="String(year.id)">{{ year.displayName }}（{{ year.yearCode }}）</option>
        </select>
      </label>
      <label>批次编号<input v-model="createForm.batchCode" maxlength="48" placeholder="例如 2026-01" required /></label>
      <label>批次名称<input v-model="createForm.name" maxlength="128" placeholder="例如 2026 年秋季互选" required /></label>
      <label class="batch-checkbox"><input v-model="createForm.supplementPlanned" type="checkbox" />安排独立补选窗口</label>
      <label class="batch-reason">追加批次原因{{ appendReasonRequired ? '（同学院同学年已有批次，必填）' : '' }}<textarea v-model="createForm.appendReason" rows="2" maxlength="4000" :required="appendReasonRequired" :placeholder="appendReasonRequired ? '请说明本次追加批次的原因' : '首次创建时可留空'" /></label>
      <button class="secondary-button primary-secondary-button" type="submit" :disabled="saving || directoryLoading || !createForm.academicYearId">{{ saving ? '正在创建…' : directoryLoading ? '正在加载学院数据…' : '创建批次草稿' }}</button>
    </form>
    <p v-if="!canCreate && selectedCollege" class="batch-readonly-note">当前授权限定到已有批次，可配置所选批次；新建批次需要学院级批次管理授权。</p>

    <div v-if="!loading && batches.length === 0" class="batch-empty batch-empty-large">
      <strong>{{ canCreate ? '还没有互选批次' : '当前授权范围内没有批次' }}</strong><span>{{ canCreate ? '先创建草稿，再配置完整排期与导师名额。' : '请联系总管理员确认批次管理范围。' }}</span>
    </div>
    <UnmatchedStudentsDialog v-if="rosterRound && selectedBatch" :key="`${selectedBatch.id}-${rosterRound}`" :batch-id="selectedBatch.id" :round-no="rosterRound" :batch-name="selectedBatch.name" @close="rosterRound = null" />
  </section>
</template>

<style scoped>
.batch-panel { color: #25364a; }
.batch-panel-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; padding-bottom: 22px; border-bottom: 1px solid #dce4eb; }
.batch-panel-heading h3 { margin: 3px 0 7px; color: #172b42; font-size: 1.5rem; letter-spacing: -.02em; }
.batch-panel-heading p:last-child { max-width: 680px; margin: 0; color: #66778a; line-height: 1.6; }
.batch-eyebrow { margin: 0; color: #54708b; font-size: .7rem; font-weight: 700; letter-spacing: .16em; }
.batch-panel-stamp,.batch-status-pill { border: 1px solid #cdd9e4; border-radius: 999px; padding: 7px 12px; color: #49637b; background: #f5f8fa; font-size: .75rem; font-weight: 700; white-space: nowrap; }
.batch-toolbar { display: grid; grid-template-columns: minmax(210px, .8fr) minmax(260px, 1.2fr); gap: 16px; margin: 22px 0; }
.batch-lifecycle-actions { display: flex; justify-content: space-between; align-items: center; gap: 14px; margin: -7px 0 18px; padding: 12px 14px; border: 1px solid #dce4eb; border-radius: 8px; background: #fbfcfd; }
.batch-lifecycle-copy { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; color: #64788a; font-size: .8rem; }
.batch-lifecycle-copy strong { color: #24394f; }
.batch-lifecycle-copy small { flex-basis: 100%; color: #64788a; line-height: 1.5; }
.batch-lifecycle-actions .batch-action-row { margin-top: 0; }
.batch-toolbar label,.batch-create-form label,.batch-inline-form label,.batch-schedule-form label { display: grid; gap: 7px; color: #53687d; font-size: .82rem; font-weight: 650; }
.batch-toolbar select,.batch-create-form input:not([type=checkbox]),.batch-create-form select,.batch-create-form textarea,.batch-inline-form input,.batch-inline-form textarea,.batch-schedule-form input,.batch-schedule-form textarea { width: 100%; min-height: 42px; border: 1px solid #cbd6df; border-radius: 7px; padding: 9px 11px; background: #fff; color: #20364d; font: inherit; }
.batch-create-form,.batch-schedule-form,.batch-quota-section { margin-top: 22px; padding: 19px; border: 1px solid #dce4eb; border-radius: 10px; background: #fbfcfd; }
.batch-statistics { margin-top: 22px; border: 1px solid #dce4eb; border-radius: 10px; background: #fbfcfd; overflow: hidden; }
.batch-statistics>.batch-subheading { padding: 19px 19px 15px; }
.batch-stat-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); border-top: 1px solid #e3e9ee; border-left: 1px solid #e3e9ee; }
.batch-stat-grid>div { display: grid; gap: 7px; min-height: 70px; align-content: center; padding: 11px 14px; border-right: 1px solid #e3e9ee; border-bottom: 1px solid #e3e9ee; background: #fff; }
.batch-stat-grid span { color: #788899; font-size: .72rem; }
.batch-stat-grid strong { color: #263f56; font-size: 1.12rem; font-variant-numeric: tabular-nums; }
.batch-round-table-wrap { overflow-x: auto; }
.batch-round-table { min-width: 680px; }
.batch-create-form { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; }
.batch-subheading { grid-column: 1/-1; display: flex; align-items: baseline; justify-content: space-between; gap: 18px; padding-bottom: 3px; color: #20364d; font-size: .98rem; font-weight: 750; }
.batch-subheading small { color: #748496; font-size: .75rem; font-weight: 450; }
.batch-checkbox { display: flex !important; align-items: center; gap: 9px !important; align-self: center; }
.batch-checkbox input { width: 16px; height: 16px; accent-color: #496f90; }
.batch-reason { grid-column: 1/-1; }
.batch-create-form textarea,.batch-inline-form textarea,.batch-schedule-form textarea { resize: vertical; }
.batch-create-form button { justify-self: start; }
.batch-readonly-note { padding: 12px 15px; border-left: 3px solid #90a9bd; color: #63788c; background: #f5f8fa; font-size: .85rem; }
.batch-selected-summary { display: flex; justify-content: space-between; align-items: center; gap: 18px; margin-top: 26px; padding: 17px 19px; border: 1px solid #d9e3ea; border-left: 4px solid #557895; border-radius: 8px; background: #f7fafc; }
.batch-selected-summary div { display: grid; gap: 4px; }
.batch-summary-label { color: #75879a; font-size: .7rem; font-weight: 700; letter-spacing: .1em; text-transform: uppercase; }
.batch-selected-summary strong { color: #1f354b; font-size: 1.12rem; }
.batch-selected-summary small { color: #718194; }
.batch-status-active { border-color: #bad7cf; color: #326d5d; background: #f0f8f5; }
.batch-status-scheduled { border-color: #c9d6e4; color: #425f7b; background: #f0f5fa; }
.batch-status-paused { border-color: #e5d4a7; color: #86641e; background: #fbf7e9; }
.batch-status-completed { border-color: #c8d9cc; color: #4a7055; background: #f2f8f3; }
.batch-status-archived,.batch-status-cancelled { border-color: #d4d9de; color: #697887; background: #f3f5f6; }
.batch-phase-strip { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); margin: 18px 0; border: 1px solid #dce4eb; border-radius: 8px; overflow: hidden; }
.batch-phase { display: flex; align-items: center; gap: 9px; min-height: 53px; padding: 9px 12px; border-right: 1px solid #dce4eb; color: #788899; background: #fff; font-size: .75rem; }
.batch-phase:last-child { border-right: 0; }
.batch-phase span { color: #99a7b4; font-size: .67rem; font-weight: 750; }
.batch-phase strong { font-weight: 650; }
.batch-phase.phase-current { color: #295e52; background: #eff8f5; }
.batch-phase.phase-current span { color: #48806f; }
.batch-inline-form { display: grid; grid-template-columns: minmax(180px, 1fr) minmax(240px, 2fr) auto; align-items: end; gap: 14px; padding: 17px 0; border-bottom: 1px solid #e1e7ec; }
.batch-schedule-head,.batch-schedule-row { display: grid; grid-template-columns: minmax(120px, .7fr) repeat(2, minmax(190px, 1fr)); gap: 12px; align-items: center; }
.batch-schedule-head { padding: 9px 10px; color: #7b8b9b; font-size: .7rem; font-weight: 750; letter-spacing: .07em; text-transform: uppercase; }
.batch-schedule-row { padding: 8px 10px; border-top: 1px solid #e6ebef; }
.batch-schedule-row strong { color: #465d73; font-size: .83rem; }
.batch-schedule-row input { min-height: 39px !important; padding: 7px 9px !important; font-size: .79rem !important; }
.batch-time-field { display: grid; gap: 5px; min-width: 0; align-self: start; }
.batch-time-field input { width: 100%; min-width: 0; box-sizing: border-box; }
.batch-time-field input[aria-invalid="true"] { border-color: #c94343; }
.batch-time-error { color: #b93636; font-size: .75rem; line-height: 1.5; }
.batch-time-help { color: #62778b; font-size: .8rem; line-height: 1.6; margin: 8px 10px; }
.batch-publish-checklist { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 18px; margin: 12px 10px 16px; padding: 11px 13px; border: 1px solid #dce4eb; border-radius: 7px; background: #fff; color: #687b8e; font-size: .78rem; }
.batch-publish-checklist strong { color: #304b63; }
.batch-publish-checklist .batch-check-ready { color: #34715e; }
.batch-schedule-form>.batch-reason { margin-top: 13px; }
.batch-action-row { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 16px; }
.batch-action-primary { margin-left: auto; }
.batch-quota-section { padding: 19px 0 0; overflow: hidden; }
.batch-quota-section>.batch-subheading { padding: 0 19px 16px; }
.supplement-teacher-section,.round-extension-section { padding: 19px; }
.supplement-teacher-section>.batch-subheading,.round-extension-section>.batch-subheading { padding: 0 0 16px; }
.supplement-teacher-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:8px; padding:0 19px 15px; }
.supplement-teacher-choice { display:flex; align-items:center; gap:10px; min-width:0; padding:10px; border:1px solid #e2e8ed; border-radius:7px; background:#fff; cursor:pointer; }
.supplement-teacher-choice input { width:16px; height:16px; accent-color:#496f90; }
.supplement-teacher-choice span { display:grid; flex:1; gap:3px; min-width:0; }.supplement-teacher-choice strong { color:#324a60; font-size:.8rem; }.supplement-teacher-choice small { color:#7c8b99; font-size:.68rem; }.supplement-teacher-choice em { color:#52715f; font-size:.68rem; font-style:normal; }
.supplement-reason { display:grid; gap:6px; padding:0 19px 13px; color:#64788a; font-size:.75rem; }
.supplement-reason textarea,.round-extension-section textarea,.round-extension-section input,.round-extension-section select { width:100%; min-height:39px; border:1px solid #cbd6df; border-radius:6px; padding:7px 9px; background:#fff; color:#20364d; font:inherit; }
.round-extension-section { display:grid; gap:12px; }
.round-extension-section label { display:grid; gap:6px; color:#64788a; font-size:.75rem; }
.batch-quota-table-wrap { overflow-x: auto; border-top: 1px solid #dce4eb; }
.batch-quota-table { width: 100%; border-collapse: collapse; text-align: left; font-size: .8rem; }
.batch-quota-table th { padding: 11px 13px; color: #748496; background: #f5f8fa; font-size: .7rem; font-weight: 700; white-space: nowrap; }
.batch-quota-table td { padding: 12px 13px; border-top: 1px solid #e7edf1; color: #53697d; white-space: nowrap; }
.batch-quota-table td:first-child { min-width: 140px; }
.batch-quota-table td:first-child strong,.batch-quota-table td:first-child small { display: block; }
.batch-quota-table td:first-child strong { color: #24394f; }
.batch-quota-table td:first-child small { margin-top: 3px; color: #8291a0; font-size: .7rem; }
.quota-input { width: 86px; min-height: 34px; border: 1px solid #cbd6df; border-radius: 6px; padding: 5px 8px; color: #20364d; font: inherit; }
.quota-eligible { color: #387260; }
.quota-remaining { color: #2b5674 !important; font-weight: 750; }
.quota-save-button { display: inline-flex; min-height: 36px; align-items: center; justify-content: center; padding: 0 14px; border: 1px solid var(--hnust-blue-dark); border-radius: 8px; background: var(--hnust-blue-dark); color: #fff; font: inherit; font-size: .75rem; font-weight: 650; cursor: pointer; transition: background-color 150ms ease, border-color 150ms ease, box-shadow 150ms ease; }
.quota-save-button:hover:not(:disabled) { border-color: #066c98; background: #066c98; }
.quota-save-button:disabled { cursor: not-allowed; opacity: .55; }
.batch-empty { padding: 19px; color: #738396; background: #f7f9fa; font-size: .84rem; }
.batch-empty-large { display: grid; gap: 5px; margin-top: 14px; padding: 32px; text-align: center; }
.batch-empty-large strong { color: #304b63; }
@media(max-width: 760px) {
  .batch-create-form { grid-template-columns: 1fr 1fr; }
  .supplement-teacher-grid { grid-template-columns:1fr; padding-left:0; padding-right:0; }
  .batch-stat-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .batch-phase-strip { grid-template-columns: 1fr 1fr; }
  .batch-phase:nth-child(2n) { border-right: 0; }
  .batch-schedule-head { display: none; }
  .batch-schedule-row { grid-template-columns: 1fr 1fr; }
  .batch-schedule-row strong { grid-column: 1/-1; }
  .batch-inline-form { grid-template-columns: 1fr; }
}
@media(max-width: 760px) { .batch-lifecycle-actions { align-items: flex-start; flex-direction: column; } }
@media(max-width: 520px) {
  .batch-toolbar,.batch-create-form { grid-template-columns: 1fr; }
  .batch-panel-heading,.batch-subheading { align-items: flex-start; flex-direction: column; }
  .batch-action-primary { margin-left: 0; }
  .batch-schedule-row { grid-template-columns: 1fr; }
}
</style>
