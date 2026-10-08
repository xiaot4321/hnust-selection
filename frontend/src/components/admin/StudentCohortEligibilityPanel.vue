<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ApiError } from '../../api/http'
import { personnelManagementService, type AcademicYearOption, type PersonRecord } from '../../services/personnelManagementService'
const props = defineProps<{ collegeId: number; years: AcademicYearOption[] }>()
const emit = defineEmits<{ busy: [value: boolean]; changed: [] }>()
const yearId = ref<number | null>(null)
const enrollmentYear = ref('')
const basis = ref('')
const loading = ref(false), saving = ref(false), previewed = ref(false)
const error = ref(''), result = ref('')
type Candidate = PersonRecord & { status: 'READY' | 'EXISTING' | 'SUCCESS' | 'FAILED'; failure: string }
const rows = ref<Candidate[]>([])
let contextVersion = 0
const pendingCount = computed(() => rows.value.filter(row => row.status === 'READY' || row.status === 'FAILED').length)
const busy = computed(() => loading.value || saving.value)
const labels = { READY: '待开放', EXISTING: '已具备资格，跳过', SUCCESS: '已开放', FAILED: '未成功' }
function message(cause: unknown): string { return cause instanceof ApiError ? cause.message : '暂时无法连接服务，请重试。' }
watch(() => props.years, years => {
  if (!years.some(year => year.id === yearId.value)) yearId.value = years[0]?.id ?? null
}, { immediate: true })
watch([() => props.collegeId, yearId, enrollmentYear], () => {
  contextVersion++; rows.value = []; previewed.value = false; error.value = ''; result.value = ''
})
async function preview(): Promise<void> {
  if (busy.value || !yearId.value || !/^\d{4}$/.test(enrollmentYear.value.trim())) return
  const version = contextVersion, college = props.collegeId, year = yearId.value, cohort = enrollmentYear.value.trim()
  loading.value = true; emit('busy', true); error.value = ''; result.value = ''; previewed.value = false; rows.value = []
  try {
    const existing = await personnelManagementService.eligibility(college, year, 'STUDENT')
    const validIds = new Set(existing.filter(row => row.status === 'ELIGIBLE').map(row => row.personId))
    const candidates = new Map<number, Candidate>()
    let page = 1
    while (true) {
      const response = await personnelManagementService.students(college, page)
      if (version !== contextVersion) return
      for (const person of response.items) {
        if (person.collegeId === college && person.enrollmentYearCode === cohort) {
          candidates.set(person.id, { ...person, status: validIds.has(person.id) ? 'EXISTING' : 'READY', failure: '' })
        }
      }
      if (page * 20 >= response.total || response.items.length === 0) break
      page++
    }
    if (version !== contextVersion) return
    rows.value = [...candidates.values()]; previewed.value = true
  } catch (cause) { if (version === contextVersion) error.value = message(cause) }
  finally { loading.value = false; emit('busy', false) }
}
async function openEligibility(): Promise<void> {
  if (busy.value || !previewed.value || !yearId.value || !basis.value.trim() || !pendingCount.value) return
  const college = props.collegeId, year = yearId.value, version = contextVersion, evidence = basis.value.trim()
  saving.value = true; emit('busy', true); error.value = ''; result.value = ''
  let completed = 0, failed = 0, skipped = 0
  try {
    // 重试前重新读取当前资格，跳过上次响应丢失但已经成功的学生。
    const current = await personnelManagementService.eligibility(college, year, 'STUDENT')
    if (version !== contextVersion) return
    const validIds = new Set(current.filter(row => row.status === 'ELIGIBLE').map(row => row.personId))
    for (const row of rows.value.filter(row => row.status === 'READY' || row.status === 'FAILED')) {
      if (version !== contextVersion) return
      if (validIds.has(row.id)) { row.status = 'EXISTING'; row.failure = ''; skipped++; continue }
      try {
        await personnelManagementService.setEligibility({ personType: 'STUDENT', personId: row.id, collegeId: college,
          academicYearId: year, eligibilityStatus: 'ELIGIBLE', evidenceType: 'COHORT_OPEN',
          evidenceReference: evidence, sourceName: `入学年份 ${enrollmentYear.value.trim()} 批量开放` })
        row.status = 'SUCCESS'; row.failure = ''; completed++
      } catch (cause) { row.status = 'FAILED'; row.failure = message(cause); failed++ }
    }
    result.value = `本次开放 ${completed} 人，跳过已有资格 ${skipped} 人，失败 ${failed} 人。${failed ? '可重试未成功的学生。' : ''}`
    emit('changed')
  } catch (cause) { error.value = message(cause) }
  finally { saving.value = false; emit('busy', false) }
}
</script>

<template>
  <section class="cohort-eligibility" aria-labelledby="cohort-eligibility-title">
    <h4 id="cohort-eligibility-title">按入学年份统一开放学生资格</h4>
    <p>使用上方选定的学院，选择参与学年和入学年份，预览名单后统一开放，无需逐个查找学生。</p>
    <div class="cohort-fields">
      <label>参与学年<select v-model.number="yearId" :disabled="busy"><option v-for="year in years" :key="year.id" :value="year.id">{{ year.displayName }}</option></select></label>
      <label>入学年份<input v-model="enrollmentYear" inputmode="numeric" maxlength="4" placeholder="例如 2026" :disabled="busy" /></label>
      <label class="cohort-basis">开放依据<textarea v-model="basis" maxlength="4000" placeholder="例如：学院确认的 2026 级已录取并报到学生名单" :disabled="busy" /></label>
    </div>
    <div class="cohort-actions">
      <button type="button" :disabled="busy || !yearId || !/^\d{4}$/.test(enrollmentYear.trim())" @click="preview">{{ loading ? '正在读取名单…' : '预览学生名单' }}</button>
      <button type="button" :disabled="busy || !previewed || !pendingCount || !basis.trim()" @click="openEligibility">{{ saving ? '正在开放资格…' : `统一开放资格（${pendingCount} 人）` }}</button>
    </div>
    <p v-if="error" role="alert">{{ error }}</p><p v-if="result" role="status">{{ result }}</p>
    <template v-if="previewed">
      <p>符合学院和入学年份的学生共 {{ rows.length }} 人，待开放 {{ pendingCount }} 人。已具备资格的学生不会重复生成资格版本。</p>
      <div class="cohort-table"><table><thead><tr><th>姓名</th><th>学号</th><th>专业</th><th>入学年份</th><th>办理状态</th></tr></thead><tbody>
        <tr v-for="row in rows" :key="row.id"><td>{{ row.fullName }}</td><td>{{ row.identifier }}</td><td>{{ row.majorName }}</td><td>{{ row.enrollmentYearCode }}</td><td>{{ labels[row.status] }}<small v-if="row.failure">{{ row.failure }}</small></td></tr>
        <tr v-if="!rows.length"><td colspan="5">没有符合条件的学生，请检查所选学院及学生档案的入学年份。</td></tr>
      </tbody></table></div>
    </template>
    <p class="cohort-note">开放作用于预览名单和所选学年。之后新增的学生需再次预览开放；账号停用及批次已冻结名单的规则仍生效。个别学生可在下方单独调整资格。</p>
  </section>
</template>

<style scoped>
.cohort-eligibility { margin-bottom: 28px; padding: 22px; border: 1px solid #cfe2ec; border-left: 4px solid var(--hnust-blue); border-radius: 8px; background: #f5fafc; }
.cohort-eligibility h4 { margin: 0 0 12px; }.cohort-eligibility p { color: var(--hnust-muted); line-height: 1.7; }
.cohort-fields { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }.cohort-fields label { display: grid; gap: 8px; font-weight: 650; }.cohort-basis { grid-column: 1 / -1; }
.cohort-fields input,.cohort-fields select,.cohort-fields textarea { padding: 12px; border: 1px solid #c9dce6; border-radius: 5px; font: inherit; background: white; }.cohort-actions { display: flex; flex-wrap: wrap; gap: 12px; margin: 18px 0; }
.cohort-actions button { padding: 11px 18px; color: white; background: var(--hnust-blue); border: 0; border-radius: 5px; font: inherit; cursor: pointer; }.cohort-actions button:disabled { opacity: .45; cursor: default; }
.cohort-table { overflow: auto; max-height: 420px; }table { width: 100%; border-collapse: collapse; text-align: left; }td,th { padding: 12px; border-bottom: 1px solid #dce7ee; }small { display: block; color: #b43d3d; }.cohort-note { font-size: 12px; }
@media(max-width:760px) { .cohort-fields { grid-template-columns:1fr; } }
</style>
