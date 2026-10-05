<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ApiError } from '../../api/http'
import {
  selectionBatchService,
  type BatchMajor,
  type TeacherScope,
  type TeacherScopeBatch,
} from '../../services/selectionBatchService'

// 批次列表由后端按当前导师身份关联的名额账户生成，不从页面参数接收 teacherId。
const batches = ref<TeacherScopeBatch[]>([])
const selectedBatchId = ref<number | null>(null)
const scope = ref<TeacherScope | null>(null)
const majors = ref<BatchMajor[]>([])
const degreeTypes = ref<string[]>([])
const majorIds = ref<number[]>([])
const loading = ref(false)
const saving = ref(false)
const errorMessage = ref('')
const successMessage = ref('')
const selectedBatch = computed(() => batches.value.find((item) => item.batchId === selectedBatchId.value) ?? null)
const allMajorsSelected = computed(() => majors.value.length > 0 && majorIds.value.length === majors.value.length)

function friendlyError(error: unknown): string {
  if (error instanceof ApiError) return error.message
  return '暂时无法连接服务，请稍后重试。'
}

function formatShanghai(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }).format(new Date(value))
}

/** 在本地编辑副本上切换学位类型；保存时作为完整集合发送。 */
function toggleDegree(type: string): void {
  degreeTypes.value = degreeTypes.value.includes(type)
    ? degreeTypes.value.filter((item) => item !== type)
    : [...degreeTypes.value, type]
}

/** 在本地编辑副本上切换专业；后端会重新校验专业启用状态和学院归属。 */
function toggleMajor(id: number): void {
  majorIds.value = majorIds.value.includes(id)
    ? majorIds.value.filter((item) => item !== id)
    : [...majorIds.value, id]
}

/** 快捷全选/清空仅修改本地选择，不会绕过保存按钮及服务端范围校验。 */
function setAllMajors(selected: boolean): void {
  majorIds.value = selected ? majors.value.map((major) => major.id) : []
}

/** 加载本人可配置批次，并优先选择当前列表中的第一项。 */
async function loadBatches(): Promise<void> {
  loading.value = true; errorMessage.value = ''
  try {
    batches.value = await selectionBatchService.teacherBatches()
    if (!selectedBatchId.value && batches.value.length) selectedBatchId.value = batches.value[0].batchId
  } catch (error) { errorMessage.value = friendlyError(error) }
  finally { loading.value = false }
}

/** 读取本批次最新范围和学院专业目录；未配置范围由服务端返回默认全选预览。 */
async function loadScope(): Promise<void> {
  if (!selectedBatch.value) { scope.value = null; majors.value = []; return }
  loading.value = true; errorMessage.value = ''; successMessage.value = ''
  try {
    const [nextScope, nextMajors] = await Promise.all([
      selectionBatchService.scope(selectedBatch.value.batchId),
      selectionBatchService.majors(selectedBatch.value.collegeId),
    ])
    scope.value = nextScope
    majors.value = nextMajors
    degreeTypes.value = [...nextScope.allowedDegreeTypes]
    majorIds.value = [...nextScope.majorIds]
  } catch (error) { errorMessage.value = friendlyError(error) }
  finally { loading.value = false }
}

/**
 * 完整替换当前导师范围。
 * 使用返回的 scope 版本作为 If-Match；如果并发冲突或窗口已开启，则重新读取服务端状态。
 */
async function save(): Promise<void> {
  if (!selectedBatch.value || !scope.value || scope.value.frozen) return
  saving.value = true; errorMessage.value = ''; successMessage.value = ''
  try {
    scope.value = await selectionBatchService.saveScope(selectedBatch.value.batchId, scope.value, {
      allowedDegreeTypes: degreeTypes.value,
      majorIds: majorIds.value,
    })
    degreeTypes.value = [...scope.value.allowedDegreeTypes]
    majorIds.value = [...scope.value.majorIds]
    successMessage.value = scope.value.defaultAllApplied ? '范围配置不完整，已按规则保存为全部学位类型和专业。' : '招生范围已保存。'
  } catch (error) { errorMessage.value = friendlyError(error); await loadScope() }
  finally { saving.value = false }
}

// 每次更换批次都重新读取对应范围，避免将一个批次的专业集合误提交到另一个批次。
watch(selectedBatchId, loadScope)
onMounted(loadBatches)
</script>

<template>
  <section class="teacher-scope-panel" aria-labelledby="teacher-scope-title">
    <div class="teacher-scope-heading">
      <div>
        <p class="teacher-scope-eyebrow">APPLICATION SCOPE</p>
        <h3 id="teacher-scope-title">招生范围配置</h3>
        <p>按批次设置允许报考的学位类型和专业。范围在学生填报开始时冻结。</p>
      </div>
      <span v-if="scope?.frozen" class="teacher-scope-frozen">已冻结</span>
    </div>

    <p v-if="errorMessage" class="form-error" role="alert">{{ errorMessage }}</p>
    <p v-if="successMessage" class="form-success" role="status">{{ successMessage }}</p>

    <!-- 只展示本人有名额账户关联的批次；历史批次保留只读查看能力。 -->
    <div v-if="batches.length" class="teacher-scope-content">
      <label class="teacher-scope-batch-label">选择批次
        <select v-model.number="selectedBatchId" :disabled="loading">
          <option v-for="batch in batches" :key="batch.batchId" :value="batch.batchId">
            {{ batch.academicYearCode }} · {{ batch.name }}（{{ batch.collegeName }}）
          </option>
        </select>
      </label>

      <template v-if="scope && selectedBatch">
        <div class="teacher-scope-context">
          <span>批次状态：{{ selectedBatch.status }}</span>
          <span v-if="selectedBatch.fillingStartAt">填报开始：{{ formatShanghai(selectedBatch.fillingStartAt) }}</span>
          <span v-else>填报开始时间尚未配置</span>
        </div>
        <fieldset class="scope-choice-group" :disabled="scope.frozen || saving || loading">
          <legend>允许的学位类型</legend>
          <label class="scope-choice"><input type="checkbox" :checked="degreeTypes.includes('ACADEMIC_MASTER')" @change="toggleDegree('ACADEMIC_MASTER')" /><span>学硕</span></label>
          <label class="scope-choice"><input type="checkbox" :checked="degreeTypes.includes('PROFESSIONAL_MASTER')" @change="toggleDegree('PROFESSIONAL_MASTER')" /><span>专硕</span></label>
        </fieldset>

        <div class="scope-majors-heading">
          <div><strong>允许的专业</strong><small>{{ majorIds.length }} / {{ majors.length }} 个专业</small></div>
          <button class="text-button" type="button" :disabled="scope.frozen || saving || loading" @click="setAllMajors(!allMajorsSelected)">{{ allMajorsSelected ? '清空选择' : '全选专业' }}</button>
        </div>
        <fieldset class="scope-major-list" :disabled="scope.frozen || saving || loading">
          <label v-for="major in majors" :key="major.id" class="scope-major-option">
            <input type="checkbox" :checked="majorIds.includes(major.id)" @change="toggleMajor(major.id)" />
            <span><strong>{{ major.name }}</strong><small>{{ major.majorCode }}</small></span>
          </label>
          <p v-if="!majors.length" class="scope-no-majors">本学院还没有启用的专业目录。</p>
        </fieldset>

        <p class="scope-default-note">若未选择学位类型或专业，系统会按规则默认开放全部学位类型和本学院启用专业。</p>
        <!-- 冻结状态以服务端响应为准；开始填报后表单禁用，接口也会拒绝后续版本写入。 -->
        <div v-if="scope.frozen" class="scope-freeze-note" role="status">
          <strong>范围已冻结</strong>
          <span>学生填报窗口已经开始，后续志愿填报和互选轮次继续使用本版本。</span>
          <small v-if="scope.frozenAt">冻结时间：{{ formatShanghai(scope.frozenAt) }}</small>
        </div>
        <button v-else class="primary-button scope-save-button" type="button" :disabled="saving || loading || !majors.length" @click="save">{{ saving ? '正在保存…' : '保存招生范围' }}<span aria-hidden="true">→</span></button>
      </template>
    </div>
    <div v-else-if="!loading" class="scope-empty-state">当前没有可配置的批次。批次发布并为导师设置名额后，会显示在这里。</div>
  </section>
</template>

<style scoped>
.teacher-scope-panel { color: #25364a; }
.teacher-scope-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; padding-bottom: 19px; border-bottom: 1px solid #dce4eb; }
.teacher-scope-heading h3 { margin: 3px 0 7px; color: #172b42; font-size: 1.45rem; }
.teacher-scope-heading p:last-child { margin: 0; color: #66778a; line-height: 1.55; }
.teacher-scope-eyebrow { margin: 0; color: #54708b; font-size: .7rem; font-weight: 700; letter-spacing: .16em; }
.teacher-scope-frozen { padding: 7px 12px; border: 1px solid #bad7cf; border-radius: 999px; color: #326d5d; background: #f0f8f5; font-size: .76rem; font-weight: 750; }
.teacher-scope-content { display: grid; gap: 17px; padding-top: 20px; }
.teacher-scope-batch-label { display: grid; gap: 7px; max-width: 480px; color: #53687d; font-size: .82rem; font-weight: 650; }
.teacher-scope-batch-label select { min-height: 42px; padding: 9px 11px; border: 1px solid #cbd6df; border-radius: 7px; background: #fff; color: #20364d; font: inherit; }
.teacher-scope-context { display: flex; flex-wrap: wrap; gap: 8px 20px; padding: 12px 14px; border: 1px solid #e0e7ec; border-radius: 7px; color: #65798b; background: #f7f9fb; font-size: .78rem; }
.scope-choice-group,.scope-major-list { min-width: 0; margin: 0; padding: 14px; border: 1px solid #dce4eb; border-radius: 8px; }
.scope-choice-group legend { padding: 0 5px; color: #344c63; font-size: .86rem; font-weight: 750; }
.scope-choice { display: inline-flex; align-items: center; gap: 8px; margin: 3px 20px 3px 0; color: #4e6478; font-size: .84rem; }
.scope-choice input,.scope-major-option input { accent-color: #496f90; }
.scope-majors-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.scope-majors-heading div { display: flex; align-items: baseline; gap: 12px; }
.scope-majors-heading strong { color: #344c63; font-size: .88rem; }
.scope-majors-heading small { color: #8391a0; font-size: .74rem; }
.scope-major-list { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; max-height: 290px; overflow: auto; }
.scope-major-option { display: flex; align-items: flex-start; gap: 9px; min-height: 50px; padding: 9px; border: 1px solid #e1e8ed; border-radius: 6px; background: #fff; }
.scope-major-option span { display: grid; gap: 3px; }
.scope-major-option strong { color: #344c63; font-size: .79rem; font-weight: 650; }
.scope-major-option small { color: #8291a0; font-size: .69rem; }
.scope-no-majors { grid-column: 1/-1; color: #8a6d3b; font-size: .82rem; }
.scope-default-note { margin: 0; color: #78899a; font-size: .76rem; line-height: 1.5; }
.scope-freeze-note { display: grid; gap: 5px; padding: 13px 15px; border-left: 3px solid #679182; color: #49685f; background: #f0f7f4; font-size: .8rem; }
.scope-freeze-note small { color: #7a938a; }
.scope-save-button { justify-self: start; }
.scope-empty-state { margin-top: 18px; padding: 24px; color: #718396; background: #f7f9fa; text-align: center; font-size: .84rem; }
@media(max-width: 680px) { .scope-major-list { grid-template-columns: 1fr 1fr; } }
@media(max-width: 460px) { .scope-major-list { grid-template-columns: 1fr; } .scope-majors-heading,.scope-majors-heading div { align-items: flex-start; flex-direction: column; } }
</style>
