import { request } from '../api/http'

export interface PageResult<T> { items: T[]; total: number; pageNo: number; pageSize: number }
export interface AdminCollege { id: number; code: string; name: string }
export interface AdminBatch { id: number; collegeId: number; academicYearId: number; yearCode: string; batchCode: string; name: string; status: string; supplementPlanned: boolean }
export interface IdentityCorrection {
  requestId: number; studentNo: string; fullName: string; collegeId: number; collegeName: string; submittedAt: string
  currentMajorName: string; currentDegreeType: string; requestedMajorName: string | null; requestedDegreeType: string | null
  studentExplanation: string; status: string; handlingComment: string | null
}
export interface TeacherProfileReview {
  versionId: number; teacherId: number; employeeNo: string; fullName: string; collegeId: number; collegeName: string
  versionNo: number; reviewStatus: string; researchDirections: string; biography: string; submittedAt: string
  currentPublishedVersionNo: number | null; currentPublishedResearchDirections: string | null
  currentPublishedBiography: string | null; etag: string
}
export interface MatchingRelation {
  relationId: number; batchId: number; studentId: number; studentNo: string; studentName: string; majorName: string
  relationStatus: string; matchReason: string | null; teacherId: number; teacherEmployeeNo: string; teacherName: string; batchStatus: string; etag: string
}
export interface ExportJob {
  exportId: number; batchId: number; exportType: string; status: 'QUEUED' | 'PROCESSING' | 'READY' | 'FAILED' | 'EXPIRED'
  createdAt: string; completedAt: string | null; expiresAt: string; rowCount: number | null; errorCode: string | null; downloadPath: string | null
}

function key(): string { return crypto.randomUUID() }

export const adminGovernanceService = {
  colleges(): Promise<AdminCollege[]> { return request('/admin/personnel/colleges') },
  batches(collegeId: number): Promise<AdminBatch[]> {
    return request(`/admin/exports/batches?collegeId=${encodeURIComponent(collegeId)}`)
  },
  identityCorrections(collegeId: number, pageNo = 1): Promise<PageResult<IdentityCorrection>> {
    return request(`/admin/identity-correction-requests?collegeId=${collegeId}&status=PENDING&pageNo=${pageNo}&pageSize=20`)
  },
  decideIdentity(requestId: number, decision: 'APPROVE' | 'REJECT', handlingComment: string): Promise<unknown> {
    return request(`/admin/identity-correction-requests/${requestId}/decision`, {
      method: 'POST', headers: { 'Idempotency-Key': key() }, body: JSON.stringify({ decision, handlingComment }),
    })
  },
  teacherProfiles(collegeId: number, pageNo = 1): Promise<PageResult<TeacherProfileReview>> {
    return request(`/admin/teacher-profile-versions?collegeId=${collegeId}&status=PENDING_REVIEW&pageNo=${pageNo}&pageSize=20`)
  },
  async teacherProfile(versionId: number): Promise<TeacherProfileReview> {
    return request(`/admin/teacher-profile-versions/${versionId}`)
  },
  reviewTeacherProfile(item: TeacherProfileReview, decision: 'APPROVE' | 'REJECT', comment: string): Promise<TeacherProfileReview> {
    return request(`/admin/teacher-profile-versions/${item.versionId}/review`, {
      method: 'POST', headers: { 'If-Match': `"${item.etag}"`, 'Idempotency-Key': key() },
      body: JSON.stringify({ decision, comment }),
    })
  },
  relations(batchId: number, pageNo = 1): Promise<PageResult<MatchingRelation>> {
    return request(`/admin/matching-relations?batchId=${batchId}&pageNo=${pageNo}&pageSize=20`)
  },
  adjustRelation(item: MatchingRelation, payload: {
    adjustmentType: 'REVOKE' | 'RESTORE' | 'REASSIGN'; reason: string; newTeacherId?: number
  }): Promise<MatchingRelation> {
    return request(`/admin/matching-relations/${item.relationId}/adjustments`, {
      method: 'POST', headers: { 'If-Match': `"${item.etag}"`, 'Idempotency-Key': key() },
      body: JSON.stringify(payload),
    })
  },
  createExport(batchId: number, exportType: 'BATCH_STATISTICS' | 'BATCH_MATCH_RESULTS'): Promise<ExportJob> {
    return request(`/admin/selection-batches/${batchId}/exports`, {
      method: 'POST', headers: { 'Idempotency-Key': key() }, body: JSON.stringify({ exportType, format: 'CSV' }),
    })
  },
  exportJob(exportId: number): Promise<ExportJob> { return request(`/admin/exports/${exportId}`) },
}
