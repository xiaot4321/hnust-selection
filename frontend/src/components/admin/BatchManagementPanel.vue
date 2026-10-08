<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ApiError } from '../../api/http'
import {
  selectionBatchService,
  type AcademicYearOption,
  type BatchCollegeOption,
  type BatchDetail,
  type BatchStage,
  type BatchSummary,
  type BatchStatistics,
  type TeacherQuota,
} from '../../services/selectionBatchService'

const props = defineProps<{ canManage: boolean }>()

// 目录与当前选择分别保存：学院变化会重载学年/批次，批次变化会重载详情和导师名额。
const colleges = ref<BatchCollegeOption[]>([])
const years = ref<AcademicYearOption[]>([])
const batches = ref<BatchSummary[]>([])
const selectedCollegeId = ref<number | null>(null)
const selectedBatchId = ref<number | null>(null)
const detail = ref<BatchDetail | null>(null)
const quotas = ref<TeacherQuota[]>([])
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
const saving = ref(false)
const errorMessage = ref('')
const successMessage = ref('')
const lifecycleFeedback = ref<{ kind: 'success' | 'error'; message: string } | null>(null)

// 这些计算值只控制页面展示；接口授权、学院/批次范围和状态仍由后端逐请求验证。
const selectedCollege = computed(() => colleges.value.find((item) => item.id === selectedCollegeId.value) ?? null)
const selectedBatch = computed(() => detail.value?.batch ?? null)
const stageOrder = ['FILLING', 'ROUND_1', 'ROUND_2', 'ROUND_3', 'SUPPLEMENT']
const visibleStages = computed(() => stageOrder.filter((code) => code !== 'SUPPLEMENT' || selectedBatch.value?.supplementPlanned))

function dismissLifecycleFeedback(): void {
  lifecycleFeedback.value = null
}
const stageNames: Record<string, string> = {
  FILLING: '学生填报', ROUND_1: '第一轮处理', ROUND_2: '第二轮处理', ROUND_3: '第三轮处理', SUPPLEMENT: '补选窗口',
}
const statusNames: Record<string, string> = {
  DRAFT: '草稿', SCHEDULED: '已发布', ACTIVE: '运行中', PAUSED: '已暂停', COMPLETED: '已完成', ARCHIVED: '已归档', CANCELLED: '已取消',
}
const isEditable = computed(() => props.canManage && ['DRAFT', 'SCHEDULED', 'ACTIVE'].includes(selectedBatch.value?.status ?? ''))
const canPublish = computed(() => props.canManage && selectedBatch.value?.status === 'DRAFT')
const canStart = computed(() => props.canManage && selectedBatch.value?.status === 'SCHEDULED')
const canCreate = computed(() => props.canManage && selectedCollege.value?.canCreateBatch === true)
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

/** 首次加载授权学院，并默认选中第一个可访问项。 */
async function loadColleges(): Promise<void> {
  loading.value = true
  try {
    colleges.value = await selectionBatchService.colleges()
    if (!selectedCollegeId.value && colleges.value.length) selectedCollegeId.value = colleges.value[0].id
  } catch (error) { errorMessage.value = friendlyError(error) }
  finally { loading.value = false }
}

/** 学院选择改变后并行加载学年和批次；若旧批次不再属于当前学院则切换到第一项。 */
async function loadCollegeData(): Promise<void> {
  if (!selectedCollegeId.value) { years.value = []; batches.value = []; return }
  loading.value = true
  errorMessage.value = ''
  try {
    const [nextYears, nextBatches] = await Promise.all([
      selectionBatchService.academicYears(selectedCollegeId.value),
      selectionBatchService.batches(selectedCollegeId.value),
    ])
    years.value = nextYears
    batches.value = nextBatches
    if (!createForm.academicYearId && nextYears.length) createForm.academicYearId = String(nextYears[0].id)
    if (!nextBatches.some((item) => item.id === selectedBatchId.value)) {
      selectedBatchId.value = nextBatches[0]?.id ?? null
    }
    if (!selectedBatchId.value) { detail.value = null; quotas.value = []; statistics.value = null }
  } catch (error) { errorMessage.value = friendlyError(error) }
  finally { loading.value = false }
}

/** 批次选择改变后并行取详情和名额表，并把服务端数据映射到可编辑表单。 */
async function loadSelectedBatch(): Promise<void> {
  if (!selectedBatchId.value) { detail.value = null; quotas.value = []; statistics.value = null; return }
  loading.value = true
  errorMessage.value = ''
  try {
    const [loaded, rows, loadedStatistics] = await Promise.all([
      selectionBatchService.batch(selectedBatchId.value), selectionBatchService.quotas(selectedBatchId.value),
      selectionBatchService.statistics(selectedBatchId.value),
    ])
    detail.value = loaded
    quotas.value = rows
    statistics.value = loadedStatistics
    if (!openRounds.value.some((round) => round.roundNo === roundExtendNo.value)) roundExtendNo.value = openRounds.value[0]?.roundNo ?? null
    if (!reopenableRounds.value.some((round) => round.roundNo === roundReopenNo.value)) roundReopenNo.value = reopenableRounds.value[0]?.roundNo ?? null
    for (const row of rows) quotaDrafts[row.teacherId] = row.quotaLimit === null ? '' : String(row.quotaLimit)
    editForm.name = loaded.batch.name
    editForm.appendReason = loaded.batch.appendReason ?? ''
    for (const stage of loaded.stages) {
      schedule[stage.stageCode] = { start: toLocalInput(stage.plannedStartAt), end: toLocalInput(stage.plannedEndAt) }
    }
  } catch (error) { errorMessage.value = friendlyError(error) }
  finally { loading.value = false }
}

/** 创建草稿后刷新目录并重新载入新批次，保证界面使用服务端返回的版本号。 */
async function createBatch(): Promise<void> {
  if (!selectedCollegeId.value || !createForm.academicYearId) return
  saving.value = true; errorMessage.value = ''; successMessage.value = ''
  try {
    const created = await selectionBatchService.create({
      collegeId: selectedCollegeId.value, academicYearId: Number(createForm.academicYearId),
      batchCode: createForm.batchCode.trim(), name: createForm.name.trim(),
      supplementPlanned: createForm.supplementPlanned, appendReason: createForm.appendReason.trim() || null,
    })
    selectedBatchId.value = created.batch.id
    createForm.batchCode = ''; createForm.name = ''; createForm.appendReason = ''; createForm.supplementPlanned = false
    await loadCollegeData()
    selectedBatchId.value = created.batch.id
    await loadSelectedBatch()
    successMessage.value = '批次草稿已创建。'
  } catch (error) { errorMessage.value = friendlyError(error) }
  finally { saving.value = false }
}

/** 元数据更新使用当前 ETag 版本；冲突或失败时重读服务端值，避免保留过期表单。 */
async function saveMetadata(): Promise<void> {
  if (!selectedBatch.value) return
  saving.value = true; errorMessage.value = ''; successMessage.value = ''
  try {
    detail.value = await selectionBatchService.update(selectedBatch.value.id, selectedBatch.value.rowVersion, {
      name: editForm.name.trim(), appendReason: editForm.appendReason.trim() || null,
    })
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
  const stages = visibleStages.value.map((stageCode) => ({
    stageCode,
    plannedStartAt: toUtc(schedule[stageCode]?.start ?? ''),
    plannedEndAt: toUtc(schedule[stageCode]?.end ?? ''),
  }))
  saving.value = true; errorMessage.value = ''; successMessage.value = ''
  try {
    detail.value = await selectionBatchService.saveSchedule(selectedBatch.value.id, selectedBatch.value.rowVersion, {
      stages, reason: editForm.scheduleReason.trim() || '批次排期配置',
    })
    editForm.scheduleReason = ''
    successMessage.value = '阶段时间已保存。'
  } catch (error) { errorMessage.value = friendlyError(error); await loadSelectedBatch() }
  finally { saving.value = false }
}

/** 批次状态命令带幂等键；完成后重读目录、状态和阶段，界面始终以服务端结果为准。 */
async function runLifecycle(action: 'publish' | 'start' | 'pause' | 'resume' | 'cancel' | 'archive' | 'unarchive'): Promise<void> {
  if (!selectedBatch.value) return
  const confirmations: Partial<Record<typeof action, string>> = {
    cancel: '取消此批次后，未匹配学生将结案为未匹配，待处理申请会取消。已锁定关系会保留。确定继续吗？',
    archive: '归档后批次进入只读历史状态。确定归档吗？',
    unarchive: '解除归档后批次回到已完成状态，供授权管理员进行审计纠错。确定继续吗？',
  }
  if (confirmations[action] && !window.confirm(confirmations[action])) return
  saving.value = true; errorMessage.value = ''; successMessage.value = ''; lifecycleFeedback.value = null
  const actionNames: Record<typeof action, string> = {
    publish: '发布批次', start: '启动批次', pause: '暂停批次', resume: '恢复批次',
    cancel: '取消批次', archive: '归档批次', unarchive: '解除归档',
  }
  const successMessages: Record<typeof action, string> = {
    publish: '批次已发布。学生端刷新后，符合本学院和本学年年度资格的学生即可看到；填报业务需等批次启动并到达填报时间。',
    start: '批次已启动。', pause: '批次已暂停。', resume: '批次已恢复，后续阶段已顺延。',
    cancel: '批次已取消，历史记录已保留。', archive: '批次已归档。', unarchive: '批次已解除归档。',
  }
  const completedStatuses: Record<typeof action, string> = {
    publish: 'SCHEDULED', start: 'ACTIVE', pause: 'PAUSED', resume: 'ACTIVE',
    cancel: 'CANCELLED', archive: 'ARCHIVED', unarchive: 'COMPLETED',
  }
  try {
    if (action === 'publish') detail.value = await selectionBatchService.publish(selectedBatch.value.id)
    else if (action === 'start') detail.value = await selectionBatchService.start(selectedBatch.value.id)
    else detail.value = await selectionBatchService.lifecycle(selectedBatch.value.id, action)
    lifecycleFeedback.value = { kind: 'success', message: successMessages[action] }
    await loadCollegeData()
    await loadSelectedBatch()
  } catch (error) {
    const commandError = friendlyError(error)
    successMessage.value = ''
    await loadSelectedBatch()
    if (!errorMessage.value && selectedBatch.value?.status === completedStatuses[action]) {
      lifecycleFeedback.value = { kind: 'success', message: `${actionNames[action]}已完成；刷新后的批次状态已确认。` }
    } else {
      const refreshError = errorMessage.value
      const currentStatus = selectedBatch.value ? (statusNames[selectedBatch.value.status] ?? selectedBatch.value.status) : '未知'
      const message = refreshError
        ? `${actionNames[action]}未能确认：${commandError}；读取当前批次状态也失败：${refreshError}`
        : `${actionNames[action]}失败：${commandError}。刷新后的批次状态：${currentStatus}。`
      lifecycleFeedback.value = { kind: 'error', message }
    }
    errorMessage.value = ''
  }
  finally { saving.value = false }
}

/** 名额数先做即时整数检查；最终资格、If-Match 版本和已占用下限由后端权威校验。 */
async function saveQuota(row: TeacherQuota): Promise<void> {
  if (!selectedBatch.value) return
  const limit = Number(quotaDrafts[row.teacherId])
  if (!Number.isInteger(limit) || limit < 0) { errorMessage.value = '名额须为大于等于 0 的整数。'; return }
  saving.value = true; errorMessage.value = ''; successMessage.value = ''
  try {
    await selectionBatchService.setQuota(selectedBatch.value.id, row, limit)
    quotas.value = await selectionBatchService.quotas(selectedBatch.value.id)
    successMessage.value = `${row.fullName} 的名额已保存。`
  } catch (error) { errorMessage.value = friendlyError(error); await loadSelectedBatch() }
  finally { saving.value = false }
}

async function extendRound(): Promise<void> {
  if (!selectedBatch.value || !roundExtendNo.value || !roundNewEnd.value) return
  if (!roundExtendReason.value.trim()) { errorMessage.value = '请填写延期原因。'; return }
  saving.value = true; errorMessage.value = ''; successMessage.value = ''
  try {
    detail.value = await selectionBatchService.extendRound(selectedBatch.value.id, roundExtendNo.value,
      selectedBatch.value.rowVersion, toUtc(roundNewEnd.value), roundExtendReason.value.trim())
    statistics.value = await selectionBatchService.statistics(selectedBatch.value.id)
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
    detail.value = await selectionBatchService.reopenRound(selectedBatch.value.id, roundReopenNo.value,
      selectedBatch.value.rowVersion, toUtc(roundReopenEnd.value), roundReopenReason.value.trim())
    statistics.value = await selectionBatchService.statistics(selectedBatch.value.id)
    roundReopenEnd.value = ''; roundReopenReason.value = ''
    successMessage.value = `第 ${roundReopenNo.value} 轮已重新开放；系统自动结案申请已恢复，后续阶段按新截止时间顺延。`
  } catch (error) { errorMessage.value = friendlyError(error); await loadSelectedBatch() }
  finally { saving.value = false }
}

// 依赖变化时自动刷新对应层级，避免切换学院后沿用上一个学院的批次详情。
watch(selectedCollegeId, loadCollegeData)
watch(selectedBatchId, loadSelectedBatch)
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

    <!-- 只有服务端目录标记为学院级授权时才显示新建表单；后端仍会再次拦截越权请求。 -->
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
      <label class="batch-reason">追加批次原因<textarea v-model="createForm.appendReason" rows="2" maxlength="4000" placeholder="同一学院和学年新增批次时必填" /></label>
      <button class="secondary-button primary-secondary-button" type="submit" :disabled="saving || loading || !createForm.academicYearId">{{ saving ? '正在创建…' : '创建批次草稿' }}</button>
    </form>
    <p v-else-if="selectedCollege" class="batch-readonly-note">当前授权限定到已有批次，可配置所选批次；新建批次需要学院级批次管理授权。</p>

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
      <form class="batch-schedule-form" @submit.prevent="saveSchedule">
        <div class="batch-subheading"><span>阶段时间</span><small>按填报、第一轮、第二轮、第三轮及补选的顺序设置</small></div>
        <div class="batch-schedule-head"><span>阶段</span><span>开始时间</span><span>结束时间</span></div>
        <div v-for="stage in visibleStages" :key="stage" class="batch-schedule-row">
          <strong>{{ stageNames[stage] }}</strong>
          <input v-model="schedule[stage].start" type="datetime-local" :disabled="!isEditable || saving" required />
          <input v-model="schedule[stage].end" type="datetime-local" :disabled="!isEditable || saving" required />
        </div>
        <label v-if="isEditable" class="batch-reason">排期变更原因<textarea v-model="editForm.scheduleReason" rows="2" maxlength="4000" placeholder="初次配置可留空；调整已发布排期时请说明原因" /></label>
        <div class="batch-action-row">
          <button v-if="isEditable" class="secondary-button" type="submit" :disabled="saving">保存完整排期</button>
          <button v-if="canPublish" class="primary-button batch-action-primary" type="button" :disabled="saving" @click="runLifecycle('publish')">发布批次 <span aria-hidden="true">→</span></button>
          <button v-if="canStart" class="primary-button batch-action-primary" type="button" :disabled="saving" @click="runLifecycle('start')">启动批次 <span aria-hidden="true">→</span></button>
          <button v-if="props.canManage && selectedBatch.status === 'ACTIVE'" class="secondary-button" type="button" :disabled="saving" @click="runLifecycle('pause')">暂停批次</button>
          <button v-if="props.canManage && selectedBatch.status === 'PAUSED'" class="primary-button" type="button" :disabled="saving" @click="runLifecycle('resume')">恢复批次</button>
          <button v-if="props.canManage && ['DRAFT', 'SCHEDULED', 'ACTIVE', 'PAUSED'].includes(selectedBatch.status)" class="text-button" type="button" :disabled="saving" @click="runLifecycle('cancel')">取消批次</button>
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
            <thead><tr><th>轮次</th><th>阶段</th><th>待处理</th><th>录取</th><th>不录取</th><th>时间跳过</th><th>批次取消</th></tr></thead>
            <tbody>
              <tr v-for="round in statistics.rounds" :key="round.roundNo">
                <td>第 {{ round.roundNo }} 轮</td><td>{{ round.stageStatus }}</td><td>{{ round.pendingApplicationCount }}</td>
                <td>{{ round.admittedCount }}</td><td>{{ round.notAdmittedCount }}</td><td>{{ round.skippedStudentCount }}</td>
                <td>{{ round.cancelledApplicationCount }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- 名额表展示剩余量和导师范围状态；实际录取时仍由服务端在名额行锁下扣减。 -->
      <div class="batch-quota-section">
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
                <td><button class="text-button" type="button" :disabled="!isEditable || saving" @click="saveQuota(row)">保存名额</button></td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </template>
    <div v-else-if="!loading && batches.length === 0" class="batch-empty batch-empty-large">
      <strong>{{ canCreate ? '还没有互选批次' : '当前授权范围内没有批次' }}</strong><span>{{ canCreate ? '先创建草稿，再配置完整排期与导师名额。' : '请联系总管理员确认批次管理范围。' }}</span>
    </div>
  </section>
  <Teleport to="body">
    <div
      v-if="lifecycleFeedback"
      class="batch-toast"
      :class="`batch-toast-${lifecycleFeedback.kind}`"
      :role="lifecycleFeedback.kind === 'error' ? 'alert' : 'status'"
      :aria-live="lifecycleFeedback.kind === 'error' ? 'assertive' : 'polite'"
    >
      <span class="batch-toast-icon" aria-hidden="true">{{ lifecycleFeedback.kind === 'success' ? '✓' : '!' }}</span>
      <div class="batch-toast-copy">
        <strong>{{ lifecycleFeedback.kind === 'success' ? '操作成功' : '操作未成功' }}</strong>
        <p>{{ lifecycleFeedback.message }}</p>
      </div>
      <button class="batch-toast-close" type="button" aria-label="关闭提示" @click="dismissLifecycleFeedback">×</button>
    </div>
  </Teleport>
</template>

<style scoped>
.batch-panel { color: #25364a; }
.batch-toast { position: fixed; z-index: 10000; top: max(16px, env(safe-area-inset-top)); right: max(16px, env(safe-area-inset-right)); display: grid; grid-template-columns: 28px minmax(0, 1fr) 28px; align-items: start; gap: 12px; width: min(520px, calc(100vw - 32px)); padding: 16px; border: 1px solid #d9e4e9; border-left: 5px solid #36806b; border-radius: 9px; background: #fff; box-shadow: 0 12px 36px rgba(22, 43, 57, .22); color: #263d4f; }
.batch-toast-success { border-left-color: #36806b; }
.batch-toast-error { border-left-color: #bd4a4a; }
.batch-toast-icon { display: grid; width: 26px; height: 26px; place-items: center; border-radius: 50%; background: #eaf5ef; color: #287052; font-weight: 800; }
.batch-toast-error .batch-toast-icon { background: #fbefef; color: #a93131; }
.batch-toast-copy strong { display: block; margin: 1px 0 4px; color: #24394f; font-size: .9rem; }
.batch-toast-copy p { margin: 0; color: #53697d; font-size: .8rem; line-height: 1.55; }
.batch-toast-close { width: 28px; height: 28px; border: 0; border-radius: 5px; background: transparent; color: #718194; cursor: pointer; font-size: 20px; line-height: 1; }
.batch-toast-close:hover { background: #f2f5f7; color: #263d4f; }
.batch-panel-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; padding-bottom: 22px; border-bottom: 1px solid #dce4eb; }
.batch-panel-heading h3 { margin: 3px 0 7px; color: #172b42; font-size: 1.5rem; letter-spacing: -.02em; }
.batch-panel-heading p:last-child { max-width: 680px; margin: 0; color: #66778a; line-height: 1.6; }
.batch-eyebrow { margin: 0; color: #54708b; font-size: .7rem; font-weight: 700; letter-spacing: .16em; }
.batch-panel-stamp,.batch-status-pill { border: 1px solid #cdd9e4; border-radius: 999px; padding: 7px 12px; color: #49637b; background: #f5f8fa; font-size: .75rem; font-weight: 700; white-space: nowrap; }
.batch-toolbar { display: grid; grid-template-columns: minmax(210px, .8fr) minmax(260px, 1.2fr); gap: 16px; margin: 22px 0; }
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
.batch-schedule-form>.batch-reason { margin-top: 13px; }
.batch-action-row { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 16px; }
.batch-action-primary { margin-left: auto; }
.batch-quota-section { padding: 19px 0 0; overflow: hidden; }
.batch-quota-section>.batch-subheading { padding: 0 19px 16px; }
.round-extension-section { padding: 19px; }
.round-extension-section>.batch-subheading { padding: 0 0 16px; }
.round-extension-section textarea,.round-extension-section input,.round-extension-section select { width:100%; min-height:39px; border:1px solid #cbd6df; border-radius:6px; padding:7px 9px; background:#fff; color:#20364d; font:inherit; }
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
.batch-empty { padding: 19px; color: #738396; background: #f7f9fa; font-size: .84rem; }
.batch-empty-large { display: grid; gap: 5px; margin-top: 14px; padding: 32px; text-align: center; }
.batch-empty-large strong { color: #304b63; }
@media(max-width: 760px) {
  .batch-create-form { grid-template-columns: 1fr 1fr; }
  .batch-stat-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .batch-phase-strip { grid-template-columns: 1fr 1fr; }
  .batch-phase:nth-child(2n) { border-right: 0; }
  .batch-schedule-head { display: none; }
  .batch-schedule-row { grid-template-columns: 1fr 1fr; }
  .batch-schedule-row strong { grid-column: 1/-1; }
  .batch-inline-form { grid-template-columns: 1fr; }
}
@media(max-width: 520px) {
  .batch-toolbar,.batch-create-form { grid-template-columns: 1fr; }
  .batch-panel-heading,.batch-subheading { align-items: flex-start; flex-direction: column; }
  .batch-action-primary { margin-left: 0; }
  .batch-schedule-row { grid-template-columns: 1fr; }
}
</style>
