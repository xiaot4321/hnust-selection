import { ApiError, request } from '../api/http'
export interface UnmatchedStudent { studentNo: string; fullName: string; collegeName: string; majorName: string }
export interface UnmatchedStudentPage { items: UnmatchedStudent[]; total: number; pageNo: number; pageSize: number }
const rosterPath = (batchId: number, roundNo: number) => `/admin/selection-batches/${batchId}/rounds/${roundNo}/unmatched-students`
export function listUnmatchedStudents(batchId: number, roundNo: number, pageNo: number): Promise<UnmatchedStudentPage> {
  return request(`${rosterPath(batchId, roundNo)}?pageNo=${pageNo}`)
}
export async function downloadUnmatchedStudents(batchId: number, roundNo: number): Promise<void> {
  const response = await fetch(`${import.meta.env.VITE_API_BASE_URL ?? '/api'}${rosterPath(batchId, roundNo)}/export`, { credentials: 'include' })
  if (!response.ok) {
    const result = await response.json().catch(() => null)
    throw new ApiError(result?.code ?? 'EXPORT_FAILED', result?.message ?? '导出失败，请稍后重试。', response.status, null)
  }
  const url = URL.createObjectURL(await response.blob())
  const link = document.createElement('a')
  link.href = url; link.download = `未完成互选学生-批次${batchId}-第${roundNo}轮.xlsx`
  document.body.appendChild(link); link.click(); link.remove()
  setTimeout(() => URL.revokeObjectURL(url), 60_000)
}
