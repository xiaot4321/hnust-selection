<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { downloadUnmatchedStudents, listUnmatchedStudents, type UnmatchedStudent } from '../../services/unmatchedStudentService'
const props = defineProps<{ batchId: number; roundNo: number; batchName: string }>()
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLDialogElement | null>(null)
const students = ref<UnmatchedStudent[]>([])
const total = ref(0), pageNo = ref(1), loading = ref(false), exporting = ref(false), error = ref('')
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / 25)))
async function load(page = 1): Promise<void> {
  if (loading.value) return
  loading.value = true; error.value = ''
  try {
    const result = await listUnmatchedStudents(props.batchId, props.roundNo, page)
    students.value = result.items; total.value = result.total; pageNo.value = result.pageNo
    if (pageNo.value > pageCount.value) {
      const last = await listUnmatchedStudents(props.batchId, props.roundNo, pageCount.value)
      students.value = last.items; total.value = last.total; pageNo.value = last.pageNo
    }
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '名单读取失败，请重试。' }
  finally { loading.value = false }
}
async function exportExcel(): Promise<void> {
  if (exporting.value) return
  exporting.value = true; error.value = ''
  try { await downloadUnmatchedStudents(props.batchId, props.roundNo) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : 'Excel 导出失败，请重试。' }
  finally { exporting.value = false }
}
onMounted(() => { dialog.value?.showModal(); void load() })
</script>
<template>
  <dialog ref="dialog" class="unmatched-dialog" aria-labelledby="unmatched-title" @cancel.prevent="emit('close')" @click="($event.target === dialog) && emit('close')">
    <header><div><h3 id="unmatched-title">未完成互选学生</h3><p>{{ batchName }} · 第 {{ roundNo }} 轮结束后</p></div><button type="button" aria-label="关闭名单" @click="emit('close')">×</button></header>
    <p class="roster-description">显示当前仍未匹配的学生（含等待后续轮次的学生），后续录取后会从名单移除。每页 25 人。</p>
    <div class="roster-actions"><span>共 {{ total }} 人</span><div><button type="button" :disabled="loading" @click="load(pageNo)">刷新名单</button><button class="export" type="button" :disabled="exporting || loading" @click="exportExcel">{{ exporting ? '正在导出…' : '导出全部 Excel' }}</button></div></div>
    <p v-if="error" class="roster-error" role="alert">{{ error }}</p>
    <div class="roster-table" :aria-busy="loading">
      <table><thead><tr><th>学号</th><th>姓名</th><th>学院</th><th>专业</th></tr></thead>
        <tbody><tr v-if="loading"><td colspan="4">正在读取名单…</td></tr><tr v-else-if="!students.length"><td colspan="4">当前没有未完成互选的学生。</td></tr><template v-else><tr v-for="student in students" :key="student.studentNo"><td>{{ student.studentNo }}</td><td>{{ student.fullName }}</td><td>{{ student.collegeName }}</td><td>{{ student.majorName }}</td></tr></template></tbody>
      </table>
    </div>
    <footer><button type="button" :disabled="loading || pageNo <= 1" @click="load(pageNo - 1)">上一页</button><span>第 {{ pageNo }} / {{ pageCount }} 页 · 每页 25 人</span><button type="button" :disabled="loading || pageNo >= pageCount" @click="load(pageNo + 1)">下一页</button></footer>
  </dialog>
</template>
<style scoped>
.unmatched-dialog{width:min(900px,calc(100vw - 32px));max-height:85vh;box-sizing:border-box;border:1px solid #dce7ee;border-radius:14px;padding:24px;color:#24465a;box-shadow:0 20px 70px #12354740;background:white}
.unmatched-dialog::backdrop{background:#17354770}header,.roster-actions,footer{display:flex;align-items:center;justify-content:space-between;gap:12px}h3{margin:0;font-size:1.25rem}header p,.roster-description{color:#708594;font-size:.82rem;line-height:1.65}header p{margin:6px 0}button{padding:8px 13px;border:1px solid #cbdde7;border-radius:6px;background:white;color:#24536a;cursor:pointer;font:inherit;font-size:.8rem}button:disabled{opacity:.45;cursor:default}header>button{font-size:1.4rem}.roster-actions{margin:16px 0;font-size:.85rem}.roster-actions>div{display:flex;gap:8px}.export{background:#008abb;color:white;border-color:#008abb}.roster-table{max-height:55vh;overflow:auto}table{width:100%;border-collapse:collapse;text-align:left;font-size:.8rem}th,td{padding:8px 12px;border-bottom:1px solid #e3edf2;white-space:nowrap}th{position:sticky;top:0;background:#eff7fa}footer{justify-content:center;margin-top:18px;font-size:.8rem}.roster-error{background:#fff0ef;color:#a43f3a;padding:10px;border-radius:6px}@media(max-width:600px){.unmatched-dialog{padding:16px}.roster-actions{align-items:flex-start;flex-direction:column}}
</style>
