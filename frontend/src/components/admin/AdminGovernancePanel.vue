<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ApiError } from '../../api/http'
import { selectionBatchService, type TeacherQuota } from '../../services/selectionBatchService'
import {
  adminGovernanceService,
  type AdminBatch,
  type AdminCollege,
  type ExportJob,
  type IdentityCorrection,
  type MatchingRelation,
  type TeacherProfileReview,
} from '../../services/adminGovernanceService'

const props = defineProps<{ canReviewPersonnel: boolean; canManageBatches: boolean }>()
const colleges = ref<AdminCollege[]>([])
const batches = ref<AdminBatch[]>([])
const selectedCollegeId = ref<number | null>(null)
const selectedBatchId = ref<number | null>(null)
const corrections = ref<IdentityCorrection[]>([])
const profiles = ref<TeacherProfileReview[]>([])
const relations = ref<MatchingRelation[]>([])
const relationQuotas = ref<TeacherQuota[]>([])
const replacementTeacherByRelation = ref<Record<number, number | null>>({})
const exports = ref<ExportJob[]>([])
const activeTab = ref<'identity' | 'profiles' | 'relations' | 'exports'>('identity')
const loading = ref(false)
const saving = ref(false)
const errorMessage = ref('')
const successMessage = ref('')

const showRelations = computed(() => props.canManageBatches)
const showExports = computed(() => props.canManageBatches || props.canReviewPersonnel)
const selectedBatch = computed(() => batches.value.find((batch) => batch.id === selectedBatchId.value) ?? null)

function errorText(error: unknown): string {
  return error instanceof ApiError ? error.message : '暂时无法连接服务，请稍后重试。'
}
function degree(value: string | null): string {
  if (value === 'ACADEMIC_MASTER') return '学硕'
  if (value === 'PROFESSIONAL_MASTER') return '专硕'
  return value ?? '未变更'
}

async function loadColleges(): Promise<void> {
  loading.value = true; errorMessage.value = ''
  try {
    const lists = await Promise.all([
      props.canReviewPersonnel ? adminGovernanceService.colleges() : Promise.resolve([]),
      props.canManageBatches ? selectionBatchService.colleges() : Promise.resolve([]),
    ])
    const union = new Map<number, AdminCollege>()
    for (const college of [...lists[0], ...lists[1]]) union.set(college.id, college)
    colleges.value = [...union.values()]
    if (!selectedCollegeId.value && colleges.value.length) selectedCollegeId.value = colleges.value[0].id
  } catch (error) { errorMessage.value = errorText(error) }
  finally { loading.value = false }
}

async function loadCollegeData(): Promise<void> {
  if (!selectedCollegeId.value) return
  loading.value = true; errorMessage.value = ''
  try {
    const requests: Promise<unknown>[] = [adminGovernanceService.batches(selectedCollegeId.value)]
    if (props.canReviewPersonnel) {
      requests.push(adminGovernanceService.identityCorrections(selectedCollegeId.value))
      requests.push(adminGovernanceService.teacherProfiles(selectedCollegeId.value))
    }
    const values = await Promise.all(requests)
    batches.value = values[0] as AdminBatch[]
    let index = 1
    if (props.canReviewPersonnel) {
      corrections.value = (values[index++] as { items: IdentityCorrection[] }).items
      profiles.value = (values[index] as { items: TeacherProfileReview[] }).items
    }
    if (!batches.value.some((batch) => batch.id === selectedBatchId.value)) selectedBatchId.value = batches.value[0]?.id ?? null
    if (showRelations.value && selectedBatchId.value) await loadRelations()
  } catch (error) { errorMessage.value = errorText(error) }
  finally { loading.value = false }
}

async function loadRelations(): Promise<void> {
  if (!selectedBatchId.value || !showRelations.value) { relations.value = []; relationQuotas.value = []; return }
  try {
    const [page, quotas] = await Promise.all([
      adminGovernanceService.relations(selectedBatchId.value),
      selectionBatchService.quotas(selectedBatchId.value),
    ])
    relations.value = page.items
    relationQuotas.value = quotas
    const selected: Record<number, number | null> = {}
    for (const item of page.items) {
      if (item.relationStatus !== 'LOCKED') continue
      const choices = replacementTeachers(item)
      const previous = replacementTeacherByRelation.value[item.relationId]
      selected[item.relationId] = choices.some((quota) => quota.teacherId === previous)
        ? previous ?? null : choices[0]?.teacherId ?? null
    }
    replacementTeacherByRelation.value = selected
  } catch (error) { errorMessage.value = errorText(error) }
}

async function reviewIdentity(item: IdentityCorrection, decision: 'APPROVE' | 'REJECT'): Promise<void> {
  let handlingComment = ''
  if (decision === 'REJECT') {
    const comment = window.prompt(`驳回 ${item.fullName}（${item.studentNo}）的更正申请，请填写原因：`)
    if (comment === null) return
    handlingComment = comment.trim()
    if (!handlingComment) { errorMessage.value = '驳回时必须填写处理意见。'; return }
  } else if (!window.confirm(`确认批准 ${item.fullName} 的身份更正申请吗？`)) return
  saving.value = true; errorMessage.value = ''; successMessage.value = ''
  try {
    await adminGovernanceService.decideIdentity(item.requestId, decision, handlingComment)
    successMessage.value = decision === 'APPROVE' ? '身份分类已更正，相关批次影响已记录。' : '申请已驳回。'
    await loadCollegeData()
  } catch (error) { errorMessage.value = errorText(error) }
  finally { saving.value = false }
}

async function reviewProfile(item: TeacherProfileReview, decision: 'APPROVE' | 'REJECT'): Promise<void> {
  let comment = ''
  if (decision === 'REJECT') {
    const value = window.prompt(`驳回 ${item.fullName} 的公开资料，请填写意见：`)
    if (value === null) return
    comment = value.trim()
    if (!comment) { errorMessage.value = '驳回时必须填写审核意见。'; return }
  } else if (!window.confirm(`确认发布 ${item.fullName} 的第 ${item.versionNo} 版公开资料吗？`)) return
  saving.value = true; errorMessage.value = ''; successMessage.value = ''
  try {
    await adminGovernanceService.reviewTeacherProfile(item, decision, comment)
    successMessage.value = decision === 'APPROVE' ? '导师资料已审核并发布。' : '资料已驳回，原公开版本继续生效。'
    await loadCollegeData()
  } catch (error) { errorMessage.value = errorText(error) }
  finally { saving.value = false }
}

function replacementTeachers(item: MatchingRelation): TeacherQuota[] {
  return relationQuotas.value.filter((quota) => quota.teacherId !== item.teacherId && quota.remainingCount > 0 && quota.scopeFrozen)
}

async function adjustRelation(item: MatchingRelation, adjustmentType: 'REVOKE' | 'RESTORE' | 'REASSIGN'): Promise<void> {
  const verb = adjustmentType === 'REVOKE' ? '撤销' : adjustmentType === 'RESTORE' ? '恢复' : '改派'
  const newTeacherId = adjustmentType === 'REASSIGN' ? replacementTeacherByRelation.value[item.relationId] : null
  if (adjustmentType === 'REASSIGN' && !newTeacherId) { errorMessage.value = '请先选择有剩余名额且已冻结招生范围的导师。'; return }
  const newTeacher = relationQuotas.value.find((quota) => quota.teacherId === newTeacherId)
  const actionDescription = adjustmentType === 'REASSIGN'
    ? `将 ${item.studentName} 从 ${item.teacherName} 改派至 ${newTeacher?.fullName ?? '所选导师'}`
    : `${verb} ${item.studentName} 与 ${item.teacherName} 的关系`
  const reason = window.prompt(`${actionDescription}，请填写原因：`)
  if (reason === null) return
  if (!reason.trim()) { errorMessage.value = '关系调整必须填写原因。'; return }
  const confirmation = adjustmentType === 'REVOKE' ? '确认撤销关系并释放导师名额吗？' :
    adjustmentType === 'RESTORE' ? '确认恢复原关系并重新占用导师名额吗？' : '确认原子撤销旧关系、释放原导师名额并建立新关系吗？'
  if (!window.confirm(`${confirmation}操作会保留历史、流水和审计，并通知相关人员。`)) return
  saving.value = true; errorMessage.value = ''; successMessage.value = ''
  try {
    await adminGovernanceService.adjustRelation(item, {
      adjustmentType, reason: reason.trim(), ...(newTeacherId ? { newTeacherId } : {}),
    })
    successMessage.value = adjustmentType === 'REVOKE' ? '关系已撤销，学生状态、导师名额和审计记录已同步更新。' :
      adjustmentType === 'RESTORE' ? '原关系已恢复，学生状态、导师名额和审计记录已同步更新。' :
        '关系已改派，原关系历史、新关系、双方名额和审计记录已同步更新。'
    await loadRelations()
  } catch (error) { errorMessage.value = errorText(error) }
  finally { saving.value = false }
}

async function startExport(exportType: 'BATCH_STATISTICS' | 'BATCH_MATCH_RESULTS'): Promise<void> {
  if (!selectedBatchId.value) return
  saving.value = true; errorMessage.value = ''; successMessage.value = ''
  try {
    const job = await adminGovernanceService.createExport(selectedBatchId.value, exportType)
    exports.value = [job, ...exports.value.filter((item) => item.exportId !== job.exportId)]
    successMessage.value = '导出任务已创建，完成后可在这里下载。文件有效期为 24 小时。'
    void pollExport(job.exportId)
  } catch (error) { errorMessage.value = errorText(error) }
  finally { saving.value = false }
}

async function pollExport(exportId: number): Promise<void> {
  for (let attempt = 0; attempt < 60; attempt += 1) {
    await new Promise((resolve) => window.setTimeout(resolve, 1500))
    try {
      const job = await adminGovernanceService.exportJob(exportId)
      exports.value = [job, ...exports.value.filter((item) => item.exportId !== exportId)]
      if (['READY', 'FAILED', 'EXPIRED'].includes(job.status)) return
    } catch (error) { errorMessage.value = errorText(error); return }
  }
}

watch(selectedCollegeId, () => { void loadCollegeData() })
watch(selectedBatchId, () => { if (selectedBatchId.value && showRelations.value) void loadRelations() })
onMounted(() => {
  if (!props.canReviewPersonnel) activeTab.value = props.canManageBatches ? 'relations' : 'exports'
  void loadColleges()
})
</script>

<template>
  <section id="admin-governance" class="governance-panel" aria-labelledby="governance-title">
    <header class="governance-heading">
      <div><p class="governance-kicker">ADMINISTRATION</p><h3 id="governance-title">审核与业务纠错</h3>
        <p>按获授学院或批次处理身份更正、导师公开资料、关系纠错与数据导出。</p></div>
      <span>{{ loading ? '正在读取' : '管理员工作台' }}</span>
    </header>
    <div class="governance-filters">
      <label>学院
        <select v-model.number="selectedCollegeId" :disabled="loading || !colleges.length">
          <option v-for="college in colleges" :key="college.id" :value="college.id">{{ college.name }}</option>
        </select>
      </label>
      <label v-if="showRelations || showExports">批次
        <select v-model.number="selectedBatchId" :disabled="loading || !batches.length">
          <option v-for="batch in batches" :key="batch.id" :value="batch.id">{{ batch.yearCode }} · {{ batch.name }}（{{ batch.status }}）</option>
        </select>
      </label>
    </div>
    <nav class="governance-tabs" aria-label="管理员业务功能">
      <button v-if="canReviewPersonnel" :class="{ active: activeTab === 'identity' }" @click="activeTab = 'identity'">身份更正 <span>{{ corrections.length }}</span></button>
      <button v-if="canReviewPersonnel" :class="{ active: activeTab === 'profiles' }" @click="activeTab = 'profiles'">导师资料审核 <span>{{ profiles.length }}</span></button>
      <button v-if="showRelations" :class="{ active: activeTab === 'relations' }" @click="activeTab = 'relations'">关系调整 <span>{{ relations.length }}</span></button>
      <button v-if="showExports" :class="{ active: activeTab === 'exports' }" @click="activeTab = 'exports'">数据导出 <span>{{ exports.length }}</span></button>
    </nav>
    <p v-if="errorMessage" class="governance-message error" role="alert">{{ errorMessage }}</p>
    <p v-if="successMessage" class="governance-message success" role="status">{{ successMessage }}</p>

    <div v-if="activeTab === 'identity' && canReviewPersonnel" class="governance-content">
      <div class="governance-subhead"><div><h4>待处理身份更正</h4><p>批准会追加身份分类版本，并按规则同步处理已开始填报的批次。</p></div><button type="button" :disabled="loading" @click="loadCollegeData">刷新</button></div>
      <div v-if="!corrections.length" class="governance-empty">当前学院没有待处理申请。</div>
      <article v-for="item in corrections" :key="item.requestId" class="governance-card">
        <div class="governance-card-title"><div><strong>{{ item.fullName }}</strong><span>{{ item.studentNo }} · {{ item.collegeName }}</span></div><small>{{ new Date(item.submittedAt).toLocaleString('zh-CN') }}</small></div>
        <div class="governance-comparison"><div><small>当前资料</small><strong>{{ item.currentMajorName }} · {{ degree(item.currentDegreeType) }}</strong></div><b aria-hidden="true">→</b><div><small>申请修改</small><strong>{{ item.requestedMajorName ?? '保持专业' }} · {{ degree(item.requestedDegreeType) }}</strong></div></div>
        <p class="governance-explanation">{{ item.studentExplanation }}</p>
        <div class="governance-actions"><button type="button" class="danger" :disabled="saving" @click="reviewIdentity(item, 'REJECT')">驳回并填写意见</button><button type="button" class="primary" :disabled="saving" @click="reviewIdentity(item, 'APPROVE')">批准更正</button></div>
      </article>
    </div>

    <div v-else-if="activeTab === 'profiles' && canReviewPersonnel" class="governance-content">
      <div class="governance-subhead"><div><h4>待审核导师公开资料</h4><p>通过后新版本进入公开目录；驳回时当前公开版本继续展示。</p></div><button type="button" :disabled="loading" @click="loadCollegeData">刷新</button></div>
      <div v-if="!profiles.length" class="governance-empty">当前学院没有待审核资料。</div>
      <article v-for="item in profiles" :key="item.versionId" class="governance-card">
        <div class="governance-card-title"><div><strong>{{ item.fullName }}</strong><span>{{ item.employeeNo }} · {{ item.collegeName }} · 版本 {{ item.versionNo }}</span></div><small>{{ new Date(item.submittedAt).toLocaleString('zh-CN') }}</small></div>
        <div class="governance-profile"><small>研究方向</small><p>{{ item.researchDirections }}</p><small>个人简介</small><p>{{ item.biography }}</p></div>
        <details v-if="item.currentPublishedVersionNo"><summary>当前公开版本 {{ item.currentPublishedVersionNo }}</summary><p>{{ item.currentPublishedResearchDirections }}</p><p>{{ item.currentPublishedBiography }}</p></details>
        <div class="governance-actions"><button type="button" class="danger" :disabled="saving" @click="reviewProfile(item, 'REJECT')">驳回并填写意见</button><button type="button" class="primary" :disabled="saving" @click="reviewProfile(item, 'APPROVE')">审核通过并发布</button></div>
      </article>
    </div>

    <div v-else-if="activeTab === 'relations' && showRelations" class="governance-content">
      <div class="governance-subhead"><div><h4>师生关系纠错</h4><p>可办理常规撤销、恢复和改派。恢复仅适用于关系撤销导致的未匹配；改派仅列出已冻结范围且有剩余名额的导师。身份纠错及缺少冻结范围等 TODO-49 特殊情况仍由服务端拒绝。</p></div><button type="button" :disabled="loading || !selectedBatchId" @click="loadRelations">刷新</button></div>
      <div v-if="!relations.length" class="governance-empty">当前批次没有关系记录。</div>
      <article v-for="item in relations" :key="item.relationId" class="governance-card relation-card">
        <div><strong>{{ item.studentName }} <small>{{ item.studentNo }}</small></strong><p>{{ item.majorName }} · {{ item.teacherName }}（{{ item.teacherEmployeeNo }}）</p></div>
        <div class="governance-actions relation-controls">
          <span class="relation-status">{{ item.relationStatus === 'LOCKED' ? '有效关系' : '已撤销' }}</span>
          <button v-if="item.relationStatus === 'LOCKED'" type="button" class="danger" :disabled="saving || item.batchStatus === 'ARCHIVED'" @click="adjustRelation(item, 'REVOKE')">撤销并释放名额</button>
          <template v-if="item.relationStatus === 'LOCKED'">
            <select v-model.number="replacementTeacherByRelation[item.relationId]" :disabled="saving || item.batchStatus === 'ARCHIVED' || !replacementTeachers(item).length" aria-label="选择改派导师">
              <option :value="null">{{ replacementTeachers(item).length ? '选择改派导师' : '暂无可用导师' }}</option>
              <option v-for="quota in replacementTeachers(item)" :key="quota.teacherId" :value="quota.teacherId">{{ quota.fullName }}（剩余 {{ quota.remainingCount }}）</option>
            </select>
            <button type="button" class="primary" :disabled="saving || item.batchStatus === 'ARCHIVED' || !replacementTeachers(item).length" @click="adjustRelation(item, 'REASSIGN')">改派导师</button>
          </template>
          <button v-if="item.relationStatus === 'REVOKED' && item.matchReason === 'RELATION_REVOKED'" type="button" class="primary" :disabled="saving || item.batchStatus === 'ARCHIVED'" @click="adjustRelation(item, 'RESTORE')">恢复关系</button>
        </div>
      </article>
    </div>

    <div v-else-if="activeTab === 'exports' && showExports" class="governance-content">
      <div class="governance-subhead"><div><h4>受控 CSV 导出</h4><p>只含姓名、学号、专业和办理结果；文件 24 小时后过期，每次创建和下载都会重新校验授权。</p></div></div>
      <div class="governance-export-buttons"><button type="button" class="secondary" :disabled="saving || !selectedBatch" @click="startExport('BATCH_STATISTICS')">生成批次统计</button><button type="button" class="primary" :disabled="saving || !selectedBatch" @click="startExport('BATCH_MATCH_RESULTS')">生成匹配结果</button></div>
      <div v-if="!exports.length" class="governance-empty">还没有导出任务。</div>
      <article v-for="job in exports" :key="job.exportId" class="governance-card export-card">
        <div><strong>{{ job.exportType === 'BATCH_STATISTICS' ? '批次统计' : '匹配结果' }} · #{{ job.exportId }}</strong><small>{{ job.status }} · 创建于 {{ new Date(job.createdAt).toLocaleString('zh-CN') }}<template v-if="job.rowCount !== null"> · {{ job.rowCount }} 行</template></small></div>
        <a v-if="job.status === 'READY' && job.downloadPath" :href="`/api${job.downloadPath}`">下载 CSV</a>
        <span v-else-if="job.status === 'FAILED'">生成失败，请重试</span><span v-else-if="job.status === 'EXPIRED'">文件已过期</span><span v-else>生成中…</span>
      </article>
    </div>
  </section>
</template>

<style scoped>
.governance-panel { margin-top: 22px; color: #26384b; }
.governance-heading,.governance-subhead,.governance-card-title { display:flex; align-items:flex-start; justify-content:space-between; gap:16px; }
.governance-heading { padding:20px; border:1px solid #dce4eb; border-radius:10px 10px 0 0; background:#f8fafb; }
.governance-heading h3 { margin:3px 0 6px; color:#183049; font-size:1.35rem; }
.governance-heading p:last-child,.governance-subhead p { margin:0; color:#718194; font-size:.82rem; line-height:1.55; }
.governance-heading>span { color:#61778c; font-size:.75rem; }
.governance-kicker { margin:0; color:#54708b; font-size:.67rem; font-weight:750; letter-spacing:.15em; }
.governance-filters { display:flex; gap:14px; padding:16px 20px; border:1px solid #dce4eb; border-top:0; background:#fff; }
.governance-filters label { display:grid; gap:6px; min-width:220px; color:#65788b; font-size:.75rem; }
.governance-filters select { min-height:38px; border:1px solid #cbd6df; border-radius:6px; padding:6px 9px; color:#20364d; background:#fff; font:inherit; }
.governance-tabs { display:flex; gap:5px; overflow:auto; padding:10px 16px 0; border:1px solid #dce4eb; border-top:0; background:#f8fafb; }
.governance-tabs button { flex:none; border:0; border-bottom:2px solid transparent; padding:11px 13px; color:#64798d; background:transparent; font:inherit; font-size:.8rem; cursor:pointer; }
.governance-tabs button.active { border-color:#4d7594; color:#234660; font-weight:700; }
.governance-tabs span { margin-left:4px; color:#8291a0; font-variant-numeric:tabular-nums; }
.governance-content { display:grid; gap:12px; padding:18px; border:1px solid #dce4eb; border-top:0; background:#fff; }
.governance-subhead { align-items:center; margin-bottom:3px; }
.governance-subhead h4 { margin:0 0 4px; color:#253c52; font-size:1rem; }
.governance-subhead button,.governance-actions button,.governance-export-buttons button { min-height:35px; border:1px solid #cbd6df; border-radius:6px; padding:7px 11px; color:#47627a; background:#fff; font:inherit; font-size:.76rem; cursor:pointer; }
.governance-card { padding:15px; border:1px solid #e0e7ec; border-radius:8px; background:#fbfcfd; }
.governance-card-title { align-items:center; margin-bottom:12px; }
.governance-card-title strong,.relation-card strong,.export-card strong { display:block; color:#2b4055; font-size:.9rem; }
.governance-card-title span,.governance-card-title small,.export-card small { display:block; margin-top:4px; color:#7c8b99; font-size:.72rem; }
.governance-comparison { display:grid; grid-template-columns:1fr auto 1fr; align-items:center; gap:14px; padding:12px; background:#f2f6f8; border-radius:6px; }
.governance-comparison div { display:grid; gap:5px; }.governance-comparison small,.governance-profile small { color:#8492a0; font-size:.69rem; }.governance-comparison strong { color:#425a70; font-size:.8rem; }.governance-comparison>b { color:#91a1af; }
.governance-explanation,.governance-profile p,.governance-card details p { margin:10px 0 0; color:#596d80; font-size:.8rem; line-height:1.6; white-space:pre-wrap; }
.governance-profile { padding:11px 12px; border-radius:6px; background:#f5f8fa; }.governance-profile p { margin:4px 0 10px; }
.governance-card details { margin-top:11px; padding:10px 12px; border:1px solid #e3e9ee; border-radius:6px; color:#60768a; font-size:.75rem; }
.governance-actions { display:flex; justify-content:flex-end; align-items:center; gap:8px; margin-top:13px; }
.relation-controls select { min-height:35px; max-width:220px; border:1px solid #cbd6df; border-radius:6px; padding:6px 9px; color:#36536b; background:#fff; font:inherit; font-size:.76rem; }
.governance-actions button.primary,.governance-export-buttons button.primary { border-color:#355f7d; color:white; background:#355f7d; }.governance-actions button.danger { border-color:#e0c5c1; color:#934f48; background:#fffafa; }
.relation-card,.export-card { display:flex; align-items:center; justify-content:space-between; gap:16px; }.relation-card p { margin:5px 0 0; color:#748597; font-size:.77rem; }.relation-status,.export-card>span { color:#728397; font-size:.75rem; }.governance-export-buttons { display:flex; gap:9px; }.governance-export-buttons button.secondary { background:#f4f7f9; }
.export-card>a { color:#315f7d; font-size:.78rem; font-weight:700; }.governance-empty { padding:22px; color:#748597; text-align:center; background:#f7f9fa; font-size:.82rem; }
.governance-message { margin:0; padding:10px 13px; border-radius:6px; font-size:.8rem; }.governance-message.error { color:#8d4942; background:#fff2ef; }.governance-message.success { color:#376d59; background:#eff8f3; }
@media(max-width:650px) { .governance-filters,.relation-card,.export-card { flex-direction:column; align-items:stretch; }.relation-controls { justify-content:flex-start; flex-wrap:wrap; }.governance-comparison { grid-template-columns:1fr; }.governance-comparison>b { transform:rotate(90deg); justify-self:center; }.governance-card-title { align-items:flex-start; flex-direction:column; } }
</style>
