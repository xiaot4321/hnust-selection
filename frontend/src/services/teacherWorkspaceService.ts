import { ApiError, request } from '../api/http'
import type { PageResult } from '../types/api'

export interface TeacherProfile {
  teacherId: number
  employeeNo: string
  fullName: string
  publishedResearchDirections: string | null
  publishedBiography: string | null
  reviewStatus: string | null
  reviewComment: string | null
  submittedResearchDirections: string | null
  submittedBiography: string | null
  versionNo: number
  etag: string
}

export interface TeacherApplication {
  applicationId: number
  batchId: number
  batchName: string
  roundNo: number
  preferenceOrder: number
  studentNo: string
  fullName: string
  majorName: string
  degreeType: string
  biography: string | null
  resumeFileId: number | null
  status: string
  enteredReviewAt: string | null
  processedAt: string | null
}

export interface TeacherSupplementApplication {
  applicationId: number
  batchId: number
  batchName: string
  studentNo: string
  fullName: string
  majorName: string
  degreeType: string
  biography: string | null
  resumeFileId: number | null
  status: string
  submittedAt: string
  processedAt: string | null
}

export interface TeacherBatchSummary {
  batchId: number
  batchName: string
  batchStatus: string
  currentStage: string | null
  quotaLimit: number
  occupiedCount: number
  remainingCount: number
  matchedStudents: Array<{
    relationId: number
    studentNo: string
    fullName: string
    majorName: string
    degreeType: string
    source: string
    lockedAt: string
  }>
}

export interface TeacherDecision {
  applicationId: number
  status: string
  relationId: number | null
  quotaLimit: number
  occupiedCount: number
  remainingCount: number
  processedAt: string
}
export interface TeacherBulkOperation {
  operationId: number
  status: string
  items: Array<{ applicationId: number; executionOrder: number; itemResult: string; code: string | null; message: string; relationId: number | null }>
}
export interface TeacherBulkAccepted { operationId: number; status: string }

export interface TeacherNoticeResult { noticeId: number; recipientCount: number; sentAt: string | null }

const pendingKeys = new Map<string, string>()
async function allPages<T>(path: (pageNo: number) => string): Promise<PageResult<T>> {
  const first = await request<PageResult<T>>(path(1))
  const items = [...first.items]
  for (let pageNo = 2; items.length < first.total; pageNo += 1) {
    const page = await request<PageResult<T>>(path(pageNo))
    if (!page.items.length) break
    items.push(...page.items)
  }
  return { ...first, items, total: first.total }
}

function keyFor(signature: string): string {
  const key = pendingKeys.get(signature)
  if (key) return key
  const created = crypto.randomUUID()
  pendingKeys.set(signature, created)
  return created
}

async function mutate<T>(signature: string, path: string, body: unknown, headers?: HeadersInit): Promise<T> {
  const allHeaders = new Headers(headers)
  allHeaders.set('Idempotency-Key', keyFor(signature))
  try {
    const result = await request<T>(path, { method: 'POST', headers: allHeaders, body: JSON.stringify(body) })
    pendingKeys.delete(signature)
    return result
  } catch (error) {
    if (error instanceof ApiError && error.status >= 400 && error.status < 500) pendingKeys.delete(signature)
    throw error
  }
}

export const teacherWorkspaceService = {
  profile(): Promise<TeacherProfile> { return request('/teachers/me/profile') },
  saveProfile(profile: TeacherProfile, payload: { researchDirections: string; biography: string }): Promise<TeacherProfile> {
    return request('/teachers/me/profile', {
      method: 'PATCH', headers: { 'If-Match': profile.etag }, body: JSON.stringify(payload),
    })
  },
  rounds(batchId: number, roundNo: number): Promise<PageResult<TeacherApplication>> {
    return allPages((pageNo) => `/teachers/me/batches/${batchId}/rounds/${roundNo}/applications?pageNo=${pageNo}&pageSize=100`)
  },
  decideRound(batchId: number, item: TeacherApplication, decision: 'ADMIT' | 'NOT_ADMITTED'): Promise<TeacherDecision> {
    const path = `/teachers/me/batches/${batchId}/round-applications/${item.applicationId}/decision`
    const body = { decision }
    return mutate(`round ${batchId} ${item.applicationId} ${decision}`, path, body)
  },
  decideRoundBatch(batchId: number, roundNo: number, items: Array<{ applicationId: number; decision: 'ADMIT' | 'NOT_ADMITTED' }>): Promise<TeacherBulkAccepted> {
    return mutate(`round batch ${batchId} ${roundNo} ${JSON.stringify(items)}`,
      `/teachers/me/batches/${batchId}/rounds/${roundNo}/decisions:batch`, { items })
  },
  supplements(batchId: number): Promise<PageResult<TeacherSupplementApplication>> {
    return allPages((pageNo) => `/teachers/me/batches/${batchId}/supplement-applications?pageNo=${pageNo}&pageSize=100`)
  },
  decideSupplement(batchId: number, item: TeacherSupplementApplication, decision: 'ADMIT' | 'NOT_ADMITTED'): Promise<TeacherDecision> {
    const path = `/teachers/me/batches/${batchId}/supplement-applications/${item.applicationId}/decision`
    return mutate(`supplement ${batchId} ${item.applicationId} ${decision}`, path, { decision })
  },
  decideSupplementBatch(batchId: number, items: Array<{ applicationId: number; decision: 'ADMIT' | 'NOT_ADMITTED' }>): Promise<TeacherBulkAccepted> {
    return mutate(`supplement batch ${batchId} ${JSON.stringify(items)}`,
      `/teachers/me/batches/${batchId}/supplement-decisions:batch`, { items })
  },
  operation(operationId: number): Promise<TeacherBulkOperation> { return request(`/operations/${operationId}`) },
  async waitForOperation(operationId: number): Promise<TeacherBulkOperation> {
    let result = await request<TeacherBulkOperation>(`/operations/${operationId}`)
    while (result.status === 'PROCESSING') {
      await new Promise<void>((resolve) => window.setTimeout(resolve, 500))
      result = await request<TeacherBulkOperation>(`/operations/${operationId}`)
    }
    return result
  },
  summary(batchId: number): Promise<TeacherBatchSummary> { return request(`/teachers/me/batches/${batchId}/summary`) },
  sendNotice(batchId: number, payload: { title: string; content: string; applicationReferences: Array<{ applicationType: 'ROUND' | 'SUPPLEMENT'; applicationId: number }> }): Promise<TeacherNoticeResult> {
    const body = JSON.stringify(payload)
    return mutate(`notice ${batchId} ${body}`, `/teachers/me/batches/${batchId}/application-notices`, payload)
  },
}
