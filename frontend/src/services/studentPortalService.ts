import { request } from '../api/http'
import type { PageResult } from '../types/api'
import type {
  IdentityCorrectionRequest,
  MajorOption,
  PreferenceSubmission,
  StudentBatchSummary,
  StudentNotice,
  StudentPreferences,
  StudentProfile,
  StudentProgress,
  SupplementApplication,
  TeacherDetail,
  TeacherDirectoryItem,
} from '../types/student'

function makeIdempotencyKey(): string {
  return crypto.randomUUID()
}
const resumeUploadKeys = new WeakMap<File, string>()

function pageQuery(pageNo = 1, pageSize = 20): string {
  const params = new URLSearchParams({ pageNo: String(pageNo), pageSize: String(pageSize) })
  return params.toString()
}

export function getStudentProfile(): Promise<StudentProfile> {
  return request<StudentProfile>('/students/me')
}

export function listStudentBatches(pageNo = 1, pageSize = 100): Promise<PageResult<StudentBatchSummary>> {
  return request<PageResult<StudentBatchSummary>>('/students/me/batches?' + pageQuery(pageNo, pageSize))
}

export function getStudentProgress(batchId: number): Promise<StudentProgress> {
  return request<StudentProgress>('/students/me/batches/' + batchId + '/progress')
}

export function getStudentPreferences(batchId: number): Promise<StudentPreferences> {
  return request<StudentPreferences>('/students/me/batches/' + batchId + '/preferences')
}

export function listPreferenceSubmissions(batchId: number, pageNo = 1, pageSize = 20): Promise<PageResult<PreferenceSubmission>> {
  return request<PageResult<PreferenceSubmission>>(
    '/students/me/batches/' + batchId + '/preference-submissions?' + pageQuery(pageNo, pageSize),
  )
}

export function submitStudentPreferences(
  batchId: number,
  identityClassificationVersion: number,
  items: Array<{ teacherId: number; preferenceOrder: number }>,
): Promise<{ submissionId: number; versionNo: number; preferenceStatus: string; submittedAt: string }> {
  return request('/students/me/batches/' + batchId + '/preferences', {
    method: 'POST',
    headers: { 'Idempotency-Key': makeIdempotencyKey() },
    body: JSON.stringify({ identityClassificationVersion, items }),
  })
}

export function withdrawStudentPreferences(batchId: number, reason?: string): Promise<{ preferenceStatus: string }> {
  return request('/students/me/batches/' + batchId + '/preferences/withdraw', {
    method: 'POST',
    headers: { 'Idempotency-Key': makeIdempotencyKey() },
    body: JSON.stringify(reason ? { reason } : {}),
  })
}

export function confirmStudentIdentity(
  batchId: number,
  classificationVersion: number,
): Promise<{ confirmedClassificationVersion: number; confirmedAt: string }> {
  return request('/students/me/batches/' + batchId + '/identity-confirmation', {
    method: 'POST',
    headers: { 'Idempotency-Key': makeIdempotencyKey() },
    body: JSON.stringify({ classificationVersion }),
  })
}

export interface TeacherDirectoryQuery {
  batchId: number
  keyword?: string
  researchDirection?: string
  canApply?: boolean
  pageNo?: number
  pageSize?: number
}

export function listTeachers(query: TeacherDirectoryQuery): Promise<PageResult<TeacherDirectoryItem>> {
  const params = new URLSearchParams({
    batchId: String(query.batchId),
    pageNo: String(query.pageNo ?? 1),
    pageSize: String(query.pageSize ?? 20),
  })
  if (query.keyword) params.set('keyword', query.keyword)
  if (query.researchDirection) params.set('researchDirection', query.researchDirection)
  if (query.canApply !== undefined) params.set('canApply', String(query.canApply))
  return request<PageResult<TeacherDirectoryItem>>('/teachers?' + params.toString())
}

export function getTeacherDetail(teacherId: number, batchId: number): Promise<TeacherDetail> {
  return request<TeacherDetail>('/teachers/' + teacherId + '?batchId=' + batchId)
}

export function listSupplementApplications(batchId: number): Promise<PageResult<SupplementApplication>> {
  return request<PageResult<SupplementApplication>>(
    '/students/me/batches/' + batchId + '/supplement-applications?' + pageQuery(),
  )
}

export function submitSupplementApplication(batchId: number, teacherId: number): Promise<SupplementApplication> {
  return request<SupplementApplication>('/students/me/batches/' + batchId + '/supplement-applications', {
    method: 'POST',
    headers: { 'Idempotency-Key': makeIdempotencyKey() },
    body: JSON.stringify({ teacherId }),
  })
}

export function updateStudentProfile(
  profileEtag: string,
  changes: { biography: string; contact: string },
): Promise<StudentProfile> {
  return request<StudentProfile>('/students/me/profile', {
    method: 'PATCH',
    headers: { 'If-Match': profileEtag },
    body: JSON.stringify(changes),
  })
}

export function uploadStudentResume(file: File): Promise<StudentProfile['resume']> {
  const form = new FormData()
  form.append('file', file)
  const key = resumeUploadKeys.get(file) ?? makeIdempotencyKey()
  resumeUploadKeys.set(file, key)
  return request<StudentProfile['resume']>('/students/me/resume', {
    method: 'PUT',
    headers: { 'Idempotency-Key': key },
    body: form,
  }).then((result) => { resumeUploadKeys.delete(file); return result })
}

export function listStudentMajors(collegeId: number): Promise<PageResult<MajorOption>> {
  const params = new URLSearchParams({
    collegeId: String(collegeId),
    active: 'true',
    pageNo: '1',
    pageSize: '100',
  })
  return request<PageResult<MajorOption>>('/majors?' + params.toString())
}

export function createIdentityCorrectionRequest(payload: {
  requestedMajorId?: number
  requestedDegreeType?: 'ACADEMIC_MASTER' | 'PROFESSIONAL_MASTER'
  studentExplanation: string
}): Promise<Pick<IdentityCorrectionRequest, 'requestId' | 'status' | 'submittedAt'>> {
  return request<Pick<IdentityCorrectionRequest, 'requestId' | 'status' | 'submittedAt'>>('/students/me/identity-correction-requests', {
    method: 'POST',
    headers: { 'Idempotency-Key': makeIdempotencyKey() },
    body: JSON.stringify(payload),
  })
}

export function listIdentityCorrectionRequests(pageNo = 1): Promise<PageResult<IdentityCorrectionRequest>> {
  return request<PageResult<IdentityCorrectionRequest>>(
    '/students/me/identity-correction-requests?' + pageQuery(pageNo, 20),
  )
}

export function listStudentNotices(): Promise<PageResult<StudentNotice>> {
  return request<PageResult<StudentNotice>>('/me/notices?' + pageQuery())
}

export function markStudentNoticeRead(noticeId: number): Promise<{ noticeId: number; readAt: string }> {
  return request<{ noticeId: number; readAt: string }>('/me/notices/' + noticeId + '/read', {
    method: 'POST',
    headers: { 'Idempotency-Key': makeIdempotencyKey() },
  })
}

export function studentFileUrl(fileId: number): string {
  const apiRoot = import.meta.env.VITE_API_BASE_URL ?? '/api'
  return apiRoot + '/files/' + fileId + '/content'
}

