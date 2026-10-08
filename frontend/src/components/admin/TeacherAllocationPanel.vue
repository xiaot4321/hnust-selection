<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ApiError } from '../../api/http'
import { personnelManagementService, type PersonRecord, type EligibilityRecord } from '../../services/personnelManagementService'
import { selectionBatchService, type TeacherQuota } from '../../services/selectionBatchService'

const props = defineProps<{ collegeId: number; collegeName: string; batchId: number; batchName: string; academicYearId: number; yearCode: string; quotas: TeacherQuota[]; editable: boolean }>()
const emit = defineEmits<{ changed: []; access: [allowed: boolean] }>()
const teachers = ref<PersonRecord[]>([])
const page = ref(1), total = ref(0), allowed = ref(false), loading = ref(false), saving = ref(false)
const selected = ref<PersonRecord | null>(null)
const eligibility = ref<EligibilityRecord | null>(null)
const eligibilityStatus = ref<'ELIGIBLE' | 'INELIGIBLE'>('ELIGIBLE')
const evidence = ref(''), error = ref(''), success = ref('')
// Vue 的 number 输入会将已填写的值转换为数字，清空时仍返回空字符串。
const quotaLimit = ref<string | number>('')
let contextVersion = 0, selectionVersion = 0, listVersion = 0
const selectedQuota = computed(() => props.quotas.find((row) => row.teacherId === selected.value?.id))
const pages = computed(() => Math.max(1, Math.ceil(total.value / 20)))
const canSaveQuota = computed(() => props.editable && !!selectedQuota.value && eligibility.value?.status === 'ELIGIBLE')
const quotaLimitValid = computed(() => {
  const value = quotaLimit.value
  const limit = Number(value)
  return String(value).trim() !== '' && Number.isSafeInteger(limit)
    && limit >= (selectedQuota.value?.occupiedCount ?? 0) && limit <= 2147483647
})
const profileNames: Record<string, string> = { DRAFT: '资料待提交', PENDING: '资料待审核', SUBMITTED: '资料待审核', APPROVED: '资料已审核', REJECTED: '资料被驳回' }
function message(cause: unknown): string { return cause instanceof ApiError ? cause.message : '加载失败，请稍后重试。' }

async function loadTeachers(nextPage = 1): Promise<void> {
  const version = contextVersion, request = ++listVersion
  loading.value = true; error.value = ''
  try {
    const result = await personnelManagementService.teachers(props.collegeId, nextPage)
    if (version !== contextVersion || request !== listVersion) return
    teachers.value = result.items; total.value = result.total; page.value = result.pageNo
  } catch (cause) { if (version === contextVersion && request === listVersion) error.value = message(cause) }
  finally { if (version === contextVersion && request === listVersion) loading.value = false }
}
watch(() => [props.collegeId, props.batchId, props.academicYearId], async () => {
  const version = ++contextVersion
  emit('access', false)
  ++selectionVersion; ++listVersion
  teachers.value = []; selected.value = null; eligibility.value = null; allowed.value = false
  error.value = ''; success.value = ''; total.value = 0; page.value = 1; loading.value = true
  try {
    const colleges = await personnelManagementService.colleges()
    if (version !== contextVersion) return
    allowed.value = colleges.some((college) => college.id === props.collegeId)
    emit('access', allowed.value)
    if (allowed.value) await loadTeachers()
  } catch (cause) { if (version === contextVersion) error.value = message(cause) }
  finally { if (version === contextVersion) loading.value = false }
}, { immediate: true })
async function selectTeacher(teacher: PersonRecord): Promise<void> {
  const version = contextVersion, request = ++selectionVersion
  selected.value = teacher; eligibility.value = null; error.value = ''; success.value = ''; evidence.value = ''
  quotaLimit.value = String(props.quotas.find((row) => row.teacherId === teacher.id)?.quotaLimit ?? '')
  loading.value = true
  try {
    const rows = await personnelManagementService.eligibility(props.collegeId, props.academicYearId, 'TEACHER', teacher.identifier)
    if (version !== contextVersion || request !== selectionVersion) return
    eligibility.value = rows.find((row) => row.personId === teacher.id) ?? null
    eligibilityStatus.value = eligibility.value?.status ?? 'ELIGIBLE'
  } catch (cause) { if (version === contextVersion && request === selectionVersion) error.value = message(cause) }
  finally { if (version === contextVersion && request === selectionVersion) loading.value = false }
}
async function saveEligibility(): Promise<void> {
  if (!selected.value || !evidence.value.trim() || saving.value || loading.value || !allowed.value) return
  const version = contextVersion, teacher = selected.value
  saving.value = true; error.value = ''; success.value = ''
  try {
    const result = await personnelManagementService.setEligibility({ personType: 'TEACHER', personId: teacher.id,
      collegeId: props.collegeId, academicYearId: props.academicYearId, eligibilityStatus: eligibilityStatus.value,
      evidenceType: 'MANUAL_CORRECTION', evidenceReference: evidence.value.trim() })
    if (version !== contextVersion) return
    eligibility.value = result; success.value = '该学年资格已保存；请继续设置本批次名额。'; emit('changed')
  } catch (cause) { if (version === contextVersion) error.value = message(cause) }
  finally { saving.value = false }
}
async function saveQuota(): Promise<void> {
  const row = selectedQuota.value
  if (!row || !canSaveQuota.value || loading.value || saving.value) return
  const limit = Number(quotaLimit.value)
  if (!quotaLimitValid.value) {
    error.value = `名额须为非负整数，且不能低于已占用人数 ${row.occupiedCount}。`; return
  }
  const version = contextVersion
  saving.value = true; error.value = ''; success.value = ''
  try {
    await selectionBatchService.setQuota(props.batchId, row, limit)
    if (version !== contextVersion) return
    success.value = '本批次导师名额已保存。'; emit('changed')
  } catch (cause) { if (version === contextVersion) { error.value = message(cause); emit('changed') } }
  finally { saving.value = false }
}
function refresh(): void { if (selected.value) { emit('changed'); void selectTeacher(selected.value) } }
</script>

<template>
  <section class="teacher-allocation" aria-labelledby="teacher-allocation-title">
    <h4 id="teacher-allocation-title">导师资格与名额</h4>
    <p class="allocation-context">{{ collegeName }} · {{ yearCode }} 学年 · {{ batchName }}</p>
    <p class="allocation-help">在上方选择学院和批次，再点击导师姓名。资格按学年保存，名额只作用于当前批次。</p>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <p v-if="success" class="form-success" role="status">{{ success }}</p>
    <p v-if="!loading && !allowed">当前账号没有该学院的人员管理权限，请联系总管理员授权。</p>
    <div v-if="allowed" class="allocation-layout">
      <div>
        <p v-if="loading" role="status">正在加载导师信息…</p>
        <p v-else-if="!teachers.length">该学院还没有导师，请先在“人员与资格”中创建导师账号。</p>
        <div class="allocation-teachers">
          <button v-for="teacher in teachers" :key="teacher.id" type="button" :aria-pressed="selected?.id === teacher.id" :disabled="saving || loading" @click="selectTeacher(teacher)">
            <strong>{{ teacher.fullName }}</strong><span>工号：{{ teacher.identifier }}</span>
            <small>{{ profileNames[teacher.profileReviewStatus ?? ''] ?? '资料未审核发布' }}</small>
          </button>
        </div>
        <div v-if="pages > 1" class="allocation-pagination">
          <button type="button" :disabled="loading || saving || page <= 1" @click="loadTeachers(page - 1)">上一页</button><span>{{ page }} / {{ pages }}</span>
          <button type="button" :disabled="loading || saving || page >= pages" @click="loadTeachers(page + 1)">下一页</button>
        </div>
      </div>
      <div v-if="selected" class="allocation-editor">
        <h5>{{ selected.fullName }} <small>{{ selected.identifier }}</small></h5>
        <form @submit.prevent="saveEligibility">
          <h6>① 设置 {{ yearCode }} 学年资格</h6>
          <p>当前资格：{{ eligibility ? (eligibility.status === 'ELIGIBLE' ? '有效' : '无效') : '尚未设置' }}</p>
          <label>资格状态<select v-model="eligibilityStatus" :disabled="loading || saving"><option value="ELIGIBLE">有效，可参加互选</option><option value="INELIGIBLE">无效，不参加互选</option></select></label>
          <label>资格依据<textarea v-model="evidence" maxlength="4000" required :disabled="loading || saving" placeholder="例如：学院审核确认该导师具有本学年招生资格" /></label>
          <button type="submit" :disabled="saving || loading || !evidence.trim()">保存学年资格</button>
        </form>
        <form @submit.prevent="saveQuota">
          <h6>② 设置本批次名额</h6>
          <p v-if="eligibility?.status !== 'ELIGIBLE'">请先保存该学年的有效资格。</p>
          <p v-else-if="!selectedQuota">导师资料尚未审核发布，或账号不可用。请先由导师提交资料，再到“管理员业务处理”审核，完成后点击刷新。</p>
          <p v-if="!editable">当前批次状态不允许设置名额。</p>
          <p v-if="selectedQuota">已占用 {{ selectedQuota.occupiedCount }} 人；当前名额 {{ selectedQuota.quotaLimit ?? '未设置' }}。</p>
          <label>招生名额上限<input v-model="quotaLimit" type="number" :min="selectedQuota?.occupiedCount ?? 0" max="2147483647" step="1" required :disabled="saving || loading || !canSaveQuota" /></label>
          <button type="submit" :disabled="saving || loading || !canSaveQuota || !quotaLimitValid">保存本批次名额</button>
          <button type="button" :disabled="saving || loading" @click="refresh">刷新资格与名额</button>
        </form>
      </div>
      <p v-else class="allocation-empty">点击左侧导师姓名，设置资格和招生名额。</p>
    </div>
  </section>
</template>

<style scoped>
.teacher-allocation { margin: 22px 0; padding: 22px; border: 1px solid #d5e3ec; border-left: 4px solid #0787b2; border-radius: 10px; background: #f8fbfd; }
h4 { margin: 0; color: #23475e; font-size: 1.1rem; }
.allocation-context { color: #33566b; font-weight: 600; }
.allocation-help,.allocation-empty { color: #647c8c; line-height: 1.7; }
.allocation-layout { display: grid; grid-template-columns: minmax(230px, 1fr) minmax(300px, 1.5fr); gap: 22px; }
.allocation-teachers { display: grid; gap: 10px; }
.allocation-teachers button { display: grid; gap: 6px; width: 100%; padding: 14px; border: 1px solid #cbdde8; border-radius: 8px; background: white; color: #2c4d62; text-align: left; cursor: pointer; }
.allocation-teachers button[aria-pressed="true"] { border-color: #008ebc; background: #eaf7fc; }
.allocation-teachers span,.allocation-teachers small { font-size: .8rem; }
.allocation-editor { padding: 18px; background: white; border: 1px solid #d9e5ec; border-radius: 8px; }
h5 { margin: 0 0 16px; font-size: 1rem; } h5 small { color: #647c8c; } h6 { font-size: .95rem; margin: 0 0 10px; }
form { display: grid; gap: 12px; } form + form { margin-top: 22px; padding-top: 22px; border-top: 1px solid #d9e5ec; }
label { display: grid; gap: 7px; } input,select,textarea { width: 100%; box-sizing: border-box; padding: 10px; border: 1px solid #cadbe6; border-radius: 6px; font: inherit; }
form button,.allocation-pagination button { padding: 10px 14px; border: 1px solid #91bacc; border-radius: 6px; background: #eef7fb; color: #24566d; cursor: pointer; }
button:disabled { opacity: .55; cursor: not-allowed; } form p { margin: 0; color: #607789; line-height: 1.7; font-size: .85rem; }
.allocation-pagination { display: flex; justify-content: space-between; align-items: center; gap: 8px; margin-top: 12px; }
@media(max-width: 760px) { .allocation-layout { grid-template-columns: 1fr; } .teacher-allocation { padding: 15px; } }
</style>
