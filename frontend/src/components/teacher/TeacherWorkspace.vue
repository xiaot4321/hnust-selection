<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ApiError } from '../../api/http'
import TeacherApplicationScopePanel from './TeacherApplicationScopePanel.vue'
import { selectionBatchService, type TeacherScopeBatch } from '../../services/selectionBatchService'
import {
  teacherWorkspaceService,
  type TeacherApplication,
  type TeacherBatchSummary,
  type TeacherProfile,
  type TeacherSupplementApplication,
} from '../../services/teacherWorkspaceService'

type Section = 'applications' | 'supplement' | 'results' | 'profile' | 'scope'
const section = ref<Section>('applications')
const sections: Array<{ id: Section; label: string }> = [
  { id: 'applications', label: '常规轮次' }, { id: 'supplement', label: '补选申请' },
  { id: 'results', label: '已录取与名额' }, { id: 'profile', label: '公开资料' }, { id: 'scope', label: '招生范围' },
]
const batches = ref<TeacherScopeBatch[]>([])
const selectedBatchId = ref<number | null>(null)
const profile = ref<TeacherProfile | null>(null)
const summary = ref<TeacherBatchSummary | null>(null)
const roundNo = ref(1)
const applications = ref<TeacherApplication[]>([])
const supplements = ref<TeacherSupplementApplication[]>([])
const selectedRoundIds = ref<number[]>([])
const selectedSupplementIds = ref<number[]>([])
const directions = ref('')
const biography = ref('')
const messageTitle = ref('互选申请沟通')
const messageContent = ref('')
const selectedNoticeRefs = ref<string[]>([])
const loading = ref(false)
const savingProfile = ref(false)
const sendingNotice = ref(false)
const busyApplications = ref(new Set<string>())
const bulkBusy = ref(false)
const errorMessage = ref('')
const successMessage = ref('')
const currentBatch = computed(() => batches.value.find((item) => item.batchId === selectedBatchId.value) ?? null)
const allNoticeCandidates = computed(() => [
  ...applications.value.map((item) => ({ key: `ROUND:${item.applicationId}`, type: 'ROUND' as const, id: item.applicationId, name: item.fullName, no: item.studentNo })),
  ...supplements.value.map((item) => ({ key: `SUPPLEMENT:${item.applicationId}`, type: 'SUPPLEMENT' as const, id: item.applicationId, name: item.fullName, no: item.studentNo })),
])
const reviewLabel = computed(() => ({
  DRAFT: '待完善', PENDING_REVIEW: '待管理员审核', APPROVED: '已审核', PUBLISHED: '已公开', REJECTED: '需修改',
}[profile.value?.reviewStatus ?? 'DRAFT'] ?? (profile.value?.reviewStatus || '尚未提交')))

function messageFor(error: unknown): string {
  if (error instanceof ApiError) return error.message
  return '暂时无法连接服务，请稍后重试。'
}
function formatTime(value: string | null | undefined): string {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(value))
}
function degreeLabel(value: string): string { return value === 'ACADEMIC_MASTER' ? '学硕' : value === 'PROFESSIONAL_MASTER' ? '专硕' : value }
function statusLabel(value: string): string { return value === 'IN_REVIEW' ? '待处理' : value === 'ADMITTED' ? '已录取' : value === 'NOT_ADMITTED' || value === 'REJECTED' ? '未录取' : value }

async function loadBatches(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    batches.value = await selectionBatchService.teacherBatches()
    if (!selectedBatchId.value && batches.value.length) selectedBatchId.value = batches.value[0].batchId
  } catch (error) { errorMessage.value = messageFor(error) }
  finally { loading.value = false }
}
async function loadProfile(): Promise<void> {
  try {
    profile.value = await teacherWorkspaceService.profile()
    directions.value = profile.value.submittedResearchDirections ?? profile.value.publishedResearchDirections ?? ''
    biography.value = profile.value.submittedBiography ?? profile.value.publishedBiography ?? ''
  } catch (error) { errorMessage.value = messageFor(error) }
}
async function loadSummary(): Promise<void> {
  if (!selectedBatchId.value) { summary.value = null; return }
  try { summary.value = await teacherWorkspaceService.summary(selectedBatchId.value) }
  catch (error) { summary.value = null; errorMessage.value = messageFor(error) }
}
async function loadRound(): Promise<void> {
  if (!selectedBatchId.value) { applications.value = []; selectedRoundIds.value = []; return }
  loading.value = true; errorMessage.value = ''; successMessage.value = ''
  try {
    const page = await teacherWorkspaceService.rounds(selectedBatchId.value, roundNo.value)
    applications.value = page.items
    selectedRoundIds.value = []
    if (page.total > page.items.length) successMessage.value = `当前显示 ${page.items.length} 条申请，共 ${page.total} 条。`
  } catch (error) { applications.value = []; errorMessage.value = messageFor(error) }
  finally { loading.value = false }
}
async function loadSupplements(): Promise<void> {
  if (!selectedBatchId.value) { supplements.value = []; selectedSupplementIds.value = []; return }
  loading.value = true; errorMessage.value = ''; successMessage.value = ''
  try { supplements.value = (await teacherWorkspaceService.supplements(selectedBatchId.value)).items; selectedSupplementIds.value = [] }
  catch (error) { supplements.value = []; errorMessage.value = messageFor(error) }
  finally { loading.value = false }
}

async function decideRound(item: TeacherApplication, decision: 'ADMIT' | 'NOT_ADMITTED'): Promise<void> {
  if (!selectedBatchId.value || busyApplications.value.has(`ROUND:${item.applicationId}`)) return
  const key = `ROUND:${item.applicationId}`
  busyApplications.value = new Set(busyApplications.value).add(key)
  errorMessage.value = ''; successMessage.value = ''
  try {
    const result = await teacherWorkspaceService.decideRound(selectedBatchId.value, item, decision)
    item.status = result.status
    item.processedAt = result.processedAt
    selectedRoundIds.value = selectedRoundIds.value.filter((id) => id !== item.applicationId)
    successMessage.value = decision === 'ADMIT' ? `${item.fullName} 已录取，师生关系已锁定。` : `已记录对 ${item.fullName} 的不录取决定。`
    await loadSummary()
  } catch (error) { errorMessage.value = messageFor(error); await loadSummary() }
  finally { const next = new Set(busyApplications.value); next.delete(key); busyApplications.value = next }
}
async function decideSupplement(item: TeacherSupplementApplication, decision: 'ADMIT' | 'NOT_ADMITTED'): Promise<void> {
  if (!selectedBatchId.value || busyApplications.value.has(`SUPPLEMENT:${item.applicationId}`)) return
  const key = `SUPPLEMENT:${item.applicationId}`
  busyApplications.value = new Set(busyApplications.value).add(key)
  errorMessage.value = ''; successMessage.value = ''
  try {
    const result = await teacherWorkspaceService.decideSupplement(selectedBatchId.value, item, decision)
    item.status = result.status
    item.processedAt = result.processedAt
    selectedSupplementIds.value = selectedSupplementIds.value.filter((id) => id !== item.applicationId)
    successMessage.value = decision === 'ADMIT' ? `${item.fullName} 已录取，关系已锁定。` : `已记录对 ${item.fullName} 的补选不录取决定。`
    await loadSummary()
  } catch (error) { errorMessage.value = messageFor(error); await loadSummary() }
  finally { const next = new Set(busyApplications.value); next.delete(key); busyApplications.value = next }
}
function toggleSelected(ids: number[], target: number, update: (next: number[]) => void): void {
  update(ids.includes(target) ? ids.filter((id) => id !== target) : [...ids, target])
}
async function decideSelectedRound(decision: 'ADMIT' | 'NOT_ADMITTED'): Promise<void> {
  if (!selectedBatchId.value || bulkBusy.value) return
  const selected = applications.value.filter((item) => selectedRoundIds.value.includes(item.applicationId) && item.status === 'IN_REVIEW')
  if (!selected.length) return
  bulkBusy.value = true; errorMessage.value = ''; successMessage.value = ''
  try {
    const accepted = await teacherWorkspaceService.decideRoundBatch(selectedBatchId.value, roundNo.value,
      selected.map((item) => ({ applicationId: item.applicationId, decision })))
    const operation = await teacherWorkspaceService.waitForOperation(accepted.operationId)
    let admitted = 0; let failed = 0
    for (const itemResult of operation.items) {
      const item = applications.value.find((candidate) => candidate.applicationId === itemResult.applicationId)
      if (!item) continue
      if (itemResult.itemResult === 'SUCCEEDED') {
        item.status = decision === 'ADMIT' ? 'ADMITTED' : 'NOT_ADMITTED'; item.processedAt = new Date().toISOString();
        if (decision === 'ADMIT') admitted += 1
      } else {
        failed += 1
        if (itemResult.code === 'QUOTA_EXHAUSTED') item.status = 'NOT_ADMITTED'
      }
    }
    const resultMessage = `批量处理完成：录取 ${admitted} 人，未按原决定完成 ${failed} 项。`
    selectedRoundIds.value = []
    await loadRound()
    await loadSummary()
    successMessage.value = resultMessage
  } catch (error) { errorMessage.value = messageFor(error); await loadSummary() }
  finally { bulkBusy.value = false }
}
async function decideSelectedSupplements(decision: 'ADMIT' | 'NOT_ADMITTED'): Promise<void> {
  if (!selectedBatchId.value || bulkBusy.value) return
  const selected = supplements.value.filter((item) => selectedSupplementIds.value.includes(item.applicationId) && item.status === 'IN_REVIEW')
  if (!selected.length) return
  bulkBusy.value = true; errorMessage.value = ''; successMessage.value = ''
  try {
    const accepted = await teacherWorkspaceService.decideSupplementBatch(selectedBatchId.value,
      selected.map((item) => ({ applicationId: item.applicationId, decision })))
    const operation = await teacherWorkspaceService.waitForOperation(accepted.operationId)
    let admitted = 0; let failed = 0
    for (const itemResult of operation.items) {
      const item = supplements.value.find((candidate) => candidate.applicationId === itemResult.applicationId)
      if (!item) continue
      if (itemResult.itemResult === 'SUCCEEDED') {
        item.status = decision === 'ADMIT' ? 'ADMITTED' : 'REJECTED'; item.processedAt = new Date().toISOString()
        if (decision === 'ADMIT') admitted += 1
      } else {
        failed += 1
        if (itemResult.code === 'QUOTA_EXHAUSTED') item.status = 'REJECTED'
      }
    }
    const resultMessage = `补选批量处理完成：录取 ${admitted} 人，未按原决定完成 ${failed} 项。`
    selectedSupplementIds.value = []
    await loadSupplements()
    await loadSummary()
    successMessage.value = resultMessage
  } catch (error) { errorMessage.value = messageFor(error); await loadSummary() }
  finally { bulkBusy.value = false }
}
async function saveProfile(): Promise<void> {
  if (!profile.value) return
  savingProfile.value = true; errorMessage.value = ''; successMessage.value = ''
  try {
    profile.value = await teacherWorkspaceService.saveProfile(profile.value, { researchDirections: directions.value, biography: biography.value })
    directions.value = profile.value.submittedResearchDirections ?? ''
    biography.value = profile.value.submittedBiography ?? ''
    successMessage.value = '资料已提交管理员审核；审核通过后会显示在学生导师目录。'
  } catch (error) { errorMessage.value = messageFor(error); await loadProfile() }
  finally { savingProfile.value = false }
}
function toggleNotice(key: string): void {
  selectedNoticeRefs.value = selectedNoticeRefs.value.includes(key)
    ? selectedNoticeRefs.value.filter((value) => value !== key) : [...selectedNoticeRefs.value, key]
}
async function sendNotice(): Promise<void> {
  if (!selectedBatchId.value || !messageTitle.value.trim() || !messageContent.value.trim() || !selectedNoticeRefs.value.length) return
  sendingNotice.value = true; errorMessage.value = ''; successMessage.value = ''
  try {
    const refs = allNoticeCandidates.value.filter((item) => selectedNoticeRefs.value.includes(item.key))
      .map(({ type, id }) => ({ applicationType: type, applicationId: id }))
    const result = await teacherWorkspaceService.sendNotice(selectedBatchId.value, {
      title: messageTitle.value.trim(), content: messageContent.value.trim(), applicationReferences: refs,
    })
    successMessage.value = `站内消息已发送给 ${result.recipientCount} 位申请学生。`
    selectedNoticeRefs.value = []; messageContent.value = ''
  } catch (error) { errorMessage.value = messageFor(error) }
  finally { sendingNotice.value = false }
}

watch(selectedBatchId, async () => {
  applications.value = []; supplements.value = []; selectedNoticeRefs.value = []
  await loadSummary()
  if (section.value === 'applications') await loadRound()
  if (section.value === 'supplement') await loadSupplements()
})
watch(section, async (next) => {
  if (next === 'profile' && !profile.value) await loadProfile()
  if (next === 'results') await loadSummary()
  if (next === 'applications') await loadRound()
  if (next === 'supplement') await loadSupplements()
})
watch(roundNo, () => { if (section.value === 'applications') void loadRound() })
onMounted(async () => { await Promise.all([loadBatches(), loadProfile()]) })
</script>

<template>
  <div class="teacher-workspace">
    <header class="teacher-dashboard-header">
      <div>
        <p class="teacher-eyebrow">TEACHER WORKSPACE</p>
        <h2>导师工作台</h2>
        <p>查看本轮申请，逐项决定录取结果，并跟进本人批次的招生情况。</p>
      </div>
      <label v-if="batches.length" class="teacher-batch-picker">当前批次
        <select v-model.number="selectedBatchId" :disabled="loading">
          <option v-for="batch in batches" :key="batch.batchId" :value="batch.batchId">{{ batch.academicYearCode }} · {{ batch.name }}</option>
        </select>
      </label>
    </header>

    <nav class="teacher-workspace-tabs" aria-label="导师工作台功能">
      <button v-for="item in sections" :key="item.id" type="button" :class="{ active: section === item.id }" @click="section = item.id">{{ item.label }}</button>
    </nav>

    <p v-if="errorMessage" class="form-error" role="alert">{{ errorMessage }}</p>
    <p v-if="successMessage" class="form-success" role="status">{{ successMessage }}</p>

    <section v-if="section === 'applications'" class="teacher-panel" aria-labelledby="round-applications-heading">
      <div class="teacher-panel-heading">
        <div><span class="teacher-kicker">CURRENT ROUND</span><h3 id="round-applications-heading">常规轮次申请</h3>
          <p>每轮只处理对应顺位投向本人的申请；导师决定不排序，录取成功后立即锁定关系。</p></div>
        <div class="round-selector" aria-label="选择轮次">
          <button v-for="number in [1, 2, 3]" :key="number" type="button" :class="{ active: roundNo === number }" @click="roundNo = number">第 {{ number }} 轮</button>
        </div>
      </div>
      <p v-if="!currentBatch" class="teacher-empty">当前没有关联导师名额的批次。</p>
      <div v-else-if="applications.length" class="teacher-application-list">
        <article v-for="item in applications" :key="item.applicationId" class="teacher-application-card" :class="{ selectable: item.status === 'IN_REVIEW' }">
          <label v-if="item.status === 'IN_REVIEW'" class="teacher-select-application" :aria-label="`选择 ${item.fullName}`"><input type="checkbox" :checked="selectedRoundIds.includes(item.applicationId)" :disabled="bulkBusy" @change="toggleSelected(selectedRoundIds, item.applicationId, (next) => selectedRoundIds = next)" /></label>
          <div class="teacher-student-avatar">{{ item.fullName.slice(0, 1) }}</div>
          <div class="teacher-application-body">
            <div class="teacher-application-title"><div><h4>{{ item.fullName }}</h4><span>{{ item.studentNo }} · {{ item.majorName }} · {{ degreeLabel(item.degreeType) }}</span></div>
              <span class="teacher-status" :class="item.status.toLowerCase()">{{ statusLabel(item.status) }}</span></div>
            <p class="teacher-biography">{{ item.biography || '学生暂未填写个人简介。' }}</p>
            <div class="teacher-application-meta"><span>第 {{ item.preferenceOrder }} 志愿</span><span>进入本轮 {{ formatTime(item.enteredReviewAt) }}</span>
              <a v-if="item.resumeFileId" :href="`/api/files/${item.resumeFileId}/content`" target="_blank" rel="noreferrer">查看简历</a>
            </div>
            <div v-if="item.status === 'IN_REVIEW'" class="teacher-decision-actions">
              <button class="teacher-admit-button" type="button" :disabled="busyApplications.has(`ROUND:${item.applicationId}`)" @click="decideRound(item, 'ADMIT')">录取并锁定</button>
              <button class="teacher-reject-button" type="button" :disabled="busyApplications.has(`ROUND:${item.applicationId}`)" @click="decideRound(item, 'NOT_ADMITTED')">不录取</button>
            </div>
          </div>
        </article>
      </div>
      <div v-if="selectedRoundIds.length" class="teacher-bulk-actions"><span>已选择 {{ selectedRoundIds.length }} 项</span><button class="teacher-admit-button" type="button" :disabled="bulkBusy" @click="decideSelectedRound('ADMIT')">{{ bulkBusy ? '处理中…' : '批量录取所选' }}</button><button class="teacher-reject-button" type="button" :disabled="bulkBusy" @click="decideSelectedRound('NOT_ADMITTED')">批量不录取</button></div>
      <div v-else-if="!applications.length" class="teacher-empty"><strong>{{ loading ? '正在读取申请…' : '当前轮暂时没有申请' }}</strong><span>填报阶段不会显示志愿；轮次开启后，只有投向本人的对应顺位申请会出现在这里。</span></div>
      <div v-if="allNoticeCandidates.length" class="teacher-message-box">
        <div><span class="teacher-kicker">MESSAGE APPLICANTS</span><h3>联系申请学生</h3><p>收件人由所选申请自动确定，不能填写其他学生或账号。</p></div>
        <fieldset class="teacher-message-recipients"><legend>收件学生</legend>
          <label v-for="candidate in allNoticeCandidates" :key="candidate.key"><input type="checkbox" :checked="selectedNoticeRefs.includes(candidate.key)" @change="toggleNotice(candidate.key)" />{{ candidate.name }}（{{ candidate.no }}）</label>
        </fieldset>
        <div class="teacher-message-form"><label>标题<input v-model="messageTitle" maxlength="120" /></label><label>消息<textarea v-model="messageContent" rows="3" maxlength="4000" placeholder="填写要发送给申请学生的站内消息"></textarea></label>
          <button class="teacher-secondary-button" type="button" :disabled="sendingNotice || !selectedNoticeRefs.length || !messageContent.trim()" @click="sendNotice">{{ sendingNotice ? '正在发送…' : `发送站内消息（${selectedNoticeRefs.length}）` }}</button>
        </div>
      </div>
    </section>

    <section v-else-if="section === 'supplement'" class="teacher-panel" aria-labelledby="supplement-heading">
      <div class="teacher-panel-heading"><div><span class="teacher-kicker">SUPPLEMENT</span><h3 id="supplement-heading">补选申请</h3><p>只处理管理员开放窗口并授权给本人的补选申请。录取时再次核验学生状态、冻结范围和剩余名额。</p></div></div>
      <div v-if="supplements.length" class="teacher-application-list">
        <article v-for="item in supplements" :key="item.applicationId" class="teacher-application-card" :class="{ selectable: item.status === 'IN_REVIEW' }">
          <label v-if="item.status === 'IN_REVIEW'" class="teacher-select-application" :aria-label="`选择 ${item.fullName}`"><input type="checkbox" :checked="selectedSupplementIds.includes(item.applicationId)" :disabled="bulkBusy" @change="toggleSelected(selectedSupplementIds, item.applicationId, (next) => selectedSupplementIds = next)" /></label>
          <div class="teacher-student-avatar">{{ item.fullName.slice(0, 1) }}</div><div class="teacher-application-body">
            <div class="teacher-application-title"><div><h4>{{ item.fullName }}</h4><span>{{ item.studentNo }} · {{ item.majorName }} · {{ degreeLabel(item.degreeType) }}</span></div><span class="teacher-status" :class="item.status.toLowerCase()">{{ statusLabel(item.status) }}</span></div>
            <p class="teacher-biography">{{ item.biography || '学生暂未填写个人简介。' }}</p><div class="teacher-application-meta"><span>提交于 {{ formatTime(item.submittedAt) }}</span><a v-if="item.resumeFileId" :href="`/api/files/${item.resumeFileId}/content`" target="_blank" rel="noreferrer">查看简历</a></div>
            <div v-if="item.status === 'IN_REVIEW'" class="teacher-decision-actions"><button class="teacher-admit-button" type="button" :disabled="busyApplications.has(`SUPPLEMENT:${item.applicationId}`)" @click="decideSupplement(item, 'ADMIT')">录取并锁定</button><button class="teacher-reject-button" type="button" :disabled="busyApplications.has(`SUPPLEMENT:${item.applicationId}`)" @click="decideSupplement(item, 'NOT_ADMITTED')">不录取</button></div>
          </div>
        </article>
      </div>
      <div v-if="selectedSupplementIds.length" class="teacher-bulk-actions"><span>已选择 {{ selectedSupplementIds.length }} 项</span><button class="teacher-admit-button" type="button" :disabled="bulkBusy" @click="decideSelectedSupplements('ADMIT')">{{ bulkBusy ? '处理中…' : '批量录取所选' }}</button><button class="teacher-reject-button" type="button" :disabled="bulkBusy" @click="decideSelectedSupplements('NOT_ADMITTED')">批量不录取</button></div>
      <div v-else-if="!supplements.length" class="teacher-empty"><strong>{{ loading ? '正在读取补选申请…' : '当前没有可处理的补选申请' }}</strong><span>窗口关闭或导师未获准参加时，补选队列不可访问。</span></div>
    </section>

    <section v-else-if="section === 'results'" class="teacher-panel" aria-labelledby="teacher-results-heading">
      <div class="teacher-panel-heading"><div><span class="teacher-kicker">MY MATCHES</span><h3 id="teacher-results-heading">已录取学生与名额</h3><p>名额在常规轮次与补选中累计使用；导师不能调整名额或自行撤销锁定关系。</p></div></div>
      <div v-if="summary" class="teacher-quota-cards"><div><small>名额上限</small><strong>{{ summary.quotaLimit }}</strong></div><div><small>已锁定关系</small><strong>{{ summary.occupiedCount }}</strong></div><div><small>剩余名额</small><strong>{{ summary.remainingCount }}</strong></div><div><small>批次阶段</small><strong>{{ summary.currentStage || summary.batchStatus }}</strong></div></div>
      <div v-if="summary?.matchedStudents.length" class="teacher-results-table-wrap"><table class="teacher-results-table"><thead><tr><th>学生</th><th>学号</th><th>专业 / 类型</th><th>录取来源</th><th>锁定时间</th></tr></thead><tbody><tr v-for="student in summary.matchedStudents" :key="student.relationId"><td>{{ student.fullName }}</td><td>{{ student.studentNo }}</td><td>{{ student.majorName }} · {{ degreeLabel(student.degreeType) }}</td><td>{{ student.source }}</td><td>{{ formatTime(student.lockedAt) }}</td></tr></tbody></table></div>
      <div v-else class="teacher-empty">当前批次还没有锁定的师生关系。</div>
    </section>

    <section v-else-if="section === 'profile'" class="teacher-panel" aria-labelledby="teacher-profile-heading">
      <div class="teacher-panel-heading"><div><span class="teacher-kicker">PUBLIC PROFILE</span><h3 id="teacher-profile-heading">维护公开资料</h3><p>学生目录展示经管理员审核通过的版本；编辑内容提交后进入审核流程。</p></div><span class="teacher-review-badge">{{ reviewLabel }}</span></div>
      <div v-if="profile" class="teacher-profile-form"><div class="teacher-profile-identity"><span class="teacher-profile-avatar">{{ profile.fullName.slice(0, 1) }}</span><div><strong>{{ profile.fullName }}</strong><span>{{ profile.employeeNo }}</span></div></div>
        <label>研究方向<textarea v-model="directions" rows="3" maxlength="2000" placeholder="介绍主要研究领域与方向"></textarea></label>
        <label>个人简介<textarea v-model="biography" rows="6" maxlength="10000" placeholder="介绍指导风格、研究团队与相关信息"></textarea></label>
        <p v-if="profile.publishedResearchDirections || profile.publishedBiography" class="teacher-published-note">当前已公开版本仍保持展示，直到新版本审核通过。</p>
        <p v-if="profile.reviewComment" class="teacher-review-comment">最近审核意见：{{ profile.reviewComment }}</p>
        <button class="teacher-admit-button" type="button" :disabled="savingProfile" @click="saveProfile">{{ savingProfile ? '正在提交…' : '提交管理员审核' }}</button>
      </div>
    </section>

    <section v-else class="teacher-panel"><TeacherApplicationScopePanel /></section>
  </div>
</template>

<style scoped>
.teacher-workspace { display: grid; gap: 18px; }
.teacher-dashboard-header,.teacher-panel-heading { display: flex; justify-content: space-between; align-items: flex-end; gap: 20px; }
.teacher-dashboard-header { padding: 4px 2px 3px; }
.teacher-dashboard-header h2,.teacher-panel-heading h3 { margin: 5px 0 7px; color: var(--hnust-blue-deep); font-size: 23px; }
.teacher-dashboard-header p,.teacher-panel-heading p { margin: 0; color: var(--hnust-muted); font-size: 13px; line-height: 1.7; }
.teacher-eyebrow,.teacher-kicker { margin: 0; color: var(--hnust-blue-dark); font-size: 10px; font-weight: 800; letter-spacing: .12em; }
.teacher-batch-picker { display: grid; gap: 6px; min-width: 250px; color: var(--hnust-muted); font-size: 12px; }
.teacher-batch-picker select,.teacher-profile-form textarea,.teacher-message-form input,.teacher-message-form textarea { width: 100%; border: 1px solid var(--hnust-line); border-radius: 4px; background: #fff; color: var(--hnust-ink); padding: 10px 11px; }
.teacher-workspace-tabs { display: flex; flex-wrap: wrap; gap: 5px; padding-bottom: 8px; border-bottom: 1px solid var(--hnust-line); }
.teacher-workspace-tabs button,.round-selector button { border: 0; background: transparent; color: var(--hnust-muted); padding: 10px 13px; cursor: pointer; }
.teacher-workspace-tabs button.active,.round-selector button.active { border-bottom: 2px solid var(--hnust-blue-dark); color: var(--hnust-blue-deep); font-weight: 700; }
.teacher-panel { display: grid; gap: 19px; padding: 22px; border: 1px solid var(--hnust-line); background: #fff; }
.teacher-panel-heading { align-items: center; }
.teacher-panel-heading h3 { font-size: 19px; }
.round-selector { display: flex; flex: 0 0 auto; gap: 2px; }
.teacher-application-list { display: grid; gap: 11px; }
.teacher-application-card { display: grid; grid-template-columns: 42px minmax(0, 1fr); gap: 14px; padding: 17px; border: 1px solid #e2eaed; background: #fbfcfd; }
.teacher-application-card.selectable { grid-template-columns: 18px 42px minmax(0,1fr); }
.teacher-select-application { align-self: start; padding-top: 12px; }
.teacher-bulk-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; padding: 12px; border: 1px solid #dce7ea; background: #f8fbfc; color: var(--hnust-muted); font-size: 12px; }
.teacher-student-avatar,.teacher-profile-avatar { display: grid; width: 40px; height: 40px; place-items: center; border-radius: 50%; background: #e7f0f3; color: var(--hnust-blue-dark); font-weight: 750; }
.teacher-application-body { min-width: 0; }
.teacher-application-title { display: flex; justify-content: space-between; gap: 15px; }
.teacher-application-title h4 { display: inline-block; margin: 0 10px 5px 0; color: var(--hnust-blue-deep); font-size: 16px; }
.teacher-application-title span:not(.teacher-status) { color: var(--hnust-muted); font-size: 12px; }
.teacher-status,.teacher-review-badge { align-self: flex-start; padding: 4px 8px; border-radius: 20px; background: #edf2f4; color: #607680; font-size: 11px; white-space: nowrap; }
.teacher-status.in_review { background: #fff2da; color: #88662f; }
.teacher-status.admitted { background: #e9f3ed; color: #54765f; }
.teacher-status.not_admitted,.teacher-status.rejected { background: #f3eded; color: #896565; }
.teacher-biography { margin: 8px 0 10px; color: #50646c; font-size: 13px; line-height: 1.65; white-space: pre-wrap; }
.teacher-application-meta { display: flex; flex-wrap: wrap; gap: 14px; color: #87969c; font-size: 11px; }
.teacher-application-meta a { color: var(--hnust-blue-dark); }
.teacher-decision-actions { display: flex; gap: 8px; margin-top: 14px; }
.teacher-admit-button,.teacher-secondary-button { border: 1px solid var(--hnust-blue-dark); border-radius: 3px; background: var(--hnust-blue-dark); color: white; padding: 9px 13px; cursor: pointer; }
.teacher-reject-button { border: 1px solid #c9d4d7; border-radius: 3px; background: white; color: #5e747c; padding: 9px 13px; cursor: pointer; }
.teacher-admit-button:disabled,.teacher-reject-button:disabled,.teacher-secondary-button:disabled { opacity: .55; cursor: wait; }
.teacher-empty { display: grid; justify-items: center; gap: 7px; padding: 36px 12px; border: 1px dashed #d7e2e5; color: #74858c; text-align: center; font-size: 13px; }
.teacher-empty strong { color: var(--hnust-blue-deep); }
.teacher-message-box { display: grid; grid-template-columns: minmax(200px, .8fr) minmax(220px, 1fr) minmax(260px, 1.2fr); gap: 15px; padding: 18px; border: 1px solid #dce7ea; background: #f8fbfc; }
.teacher-message-box h3 { margin: 6px 0; color: var(--hnust-blue-deep); font-size: 16px; }
.teacher-message-box p { margin: 0; color: var(--hnust-muted); font-size: 12px; line-height: 1.6; }
.teacher-message-recipients { display: grid; align-content: start; gap: 8px; max-height: 180px; overflow: auto; border: 1px solid #e1e9eb; padding: 10px; color: var(--hnust-ink); font-size: 12px; }
.teacher-message-recipients label { display: flex; gap: 7px; }
.teacher-message-form { display: grid; align-content: start; gap: 9px; }
.teacher-message-form label,.teacher-profile-form label { display: grid; gap: 6px; color: var(--hnust-muted); font-size: 12px; }
.teacher-message-form textarea,.teacher-profile-form textarea { resize: vertical; }
.teacher-secondary-button { justify-self: start; }
.teacher-quota-cards { display: grid; grid-template-columns: repeat(4,minmax(0,1fr)); gap: 9px; }
.teacher-quota-cards div { display: grid; gap: 7px; padding: 15px; border: 1px solid #e1e9eb; background: #fbfcfd; }
.teacher-quota-cards small { color: var(--hnust-muted); font-size: 11px; }
.teacher-quota-cards strong { color: var(--hnust-blue-deep); font-size: 21px; }
.teacher-results-table-wrap { overflow-x: auto; }
.teacher-results-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.teacher-results-table th,.teacher-results-table td { padding: 11px 9px; border-bottom: 1px solid #e7edef; text-align: left; white-space: nowrap; }
.teacher-results-table th { color: var(--hnust-muted); font-weight: 650; }
.teacher-review-badge { border-radius: 3px; background: #f1f5f6; }
.teacher-profile-form { display: grid; max-width: 760px; gap: 15px; }
.teacher-profile-identity { display: flex; align-items: center; gap: 11px; padding-bottom: 12px; border-bottom: 1px solid #e4ecee; }
.teacher-profile-identity div { display: grid; gap: 4px; }
.teacher-profile-identity strong { color: var(--hnust-blue-deep); }
.teacher-profile-identity span:not(.teacher-profile-avatar) { color: var(--hnust-muted); font-size: 12px; }
.teacher-published-note,.teacher-review-comment { margin: 0; color: var(--hnust-muted); font-size: 12px; }
.teacher-review-comment { padding: 10px; background: #f6f8f8; }
.teacher-workspace button:focus-visible,.teacher-workspace a:focus-visible,.teacher-workspace select:focus-visible,.teacher-workspace input:focus-visible,.teacher-workspace textarea:focus-visible { outline: 3px solid #6d9caf; outline-offset: 2px; }
@media (max-width: 760px) { .teacher-dashboard-header,.teacher-panel-heading { align-items: stretch; flex-direction: column; } .teacher-batch-picker { min-width: 0; } .teacher-message-box { grid-template-columns: 1fr; } .teacher-quota-cards { grid-template-columns: repeat(2,1fr); } .teacher-panel { padding: 15px; } }
</style>
