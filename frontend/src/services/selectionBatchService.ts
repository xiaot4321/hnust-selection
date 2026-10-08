import { ApiError, request } from '../api/http'

// 下列接口类型仅描述 API 的管理/导师视图，不复用数据库 Entity，避免前端依赖内部字段。
export interface BatchCollegeOption { id: number; code: string; name: string; canCreateBatch: boolean }
export interface AcademicYearOption { id: number; yearCode: string; displayName: string }
export interface BatchSummary {
  id: number; collegeId: number; collegeName: string; academicYearId: number; yearCode: string
  batchCode: string; name: string; status: string; supplementPlanned: boolean; appendReason: string | null
  publishedAt: string | null; startedAt: string | null; rowVersion: number
}
export interface BatchStage {
  id: number; stageCode: string; stageOrder: number; status: string; closeReason: string | null
  plannedStartAt: string | null; plannedEndAt: string | null
  effectiveStartAt: string | null; effectiveEndAt: string | null
}
export interface BatchDetail { batch: BatchSummary; stages: BatchStage[] }
export interface BatchMajor { id: number; collegeId: number; majorCode: string; name: string; active: boolean }
export interface TeacherQuota {
  teacherId: number; employeeNo: string; fullName: string; profileStatus: string; eligibilityBasis: string
  quotaLimit: number | null; occupiedCount: number; remainingCount: number; rowVersion: number
  scopeConfigured: boolean; scopeFrozen: boolean
}
export interface BatchRoundStatistics {
  roundNo: number; stageStatus: string; pendingApplicationCount: number; admittedCount: number
  notAdmittedCount: number; skippedStudentCount: number; cancelledApplicationCount: number
}
export interface BatchStatistics {
  batchId: number; frozenDenominator: number | null; matchedCount: number; currentUnmatchedCount: number
  normalPreferenceUnmatchedCount: number; notSubmittedCount: number; allRemainingPreferencesSkippedCount: number
  identityCorrectionUnmatchedCount: number; relationCorrectionUnmatchedCount: number; batchCancelledUnmatchedCount: number
  supplementAdmittedCount: number; supplementStillUnmatchedCount: number | null
  quotaLimit: number; occupiedQuota: number; remainingQuota: number; rounds: BatchRoundStatistics[]
}
export interface TeacherScopeBatch {
  batchId: number; collegeId: number; collegeName: string; academicYearCode: string; batchCode: string
  name: string; status: string; fillingStartAt: string | null; scopeFrozen: boolean
}
export interface TeacherScope {
  batchId: number; teacherId: number; versionNo: number; allowedDegreeTypes: string[]; majorIds: number[]
  scopeSource: string; defaultAllApplied: boolean; configuredAt: string | null; frozenAt: string | null; frozen: boolean
}

const pendingKeys = new Map<string, string>()

/**
 * 为当前待重试的有副作用命令复用幂等键。
 *
 * <p>相同请求签名在收到明确 4xx 后可以生成新键；网络中断或服务端 5xx 时保留旧键，
 * 因为客户端无法确定服务端是否已经提交，重试应当命中原操作。</p>
 */
function operationKey(signature: string): string {
  const existing = pendingKeys.get(signature)
  if (existing) return existing
  const key = crypto.randomUUID()
  pendingKeys.set(signature, key)
  return key
}

/** 为有副作用的 POST/名额 PUT 附加 Idempotency-Key，并按响应确定是否清理本地待重试键。 */
async function mutate<T>(signature: string, path: string, init: RequestInit): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('Idempotency-Key', operationKey(signature))
  try {
    const result = await request<T>(path, { ...init, headers })
    pendingKeys.delete(signature)
    return result
  } catch (error) {
    if (error instanceof ApiError && error.status >= 400 && error.status < 500) pendingKeys.delete(signature)
    throw error
  }
}

/** 使用相同 JSON 序列化方式生成幂等签名，确保请求体改变时使用不同键。 */
function json(value: unknown): string { return JSON.stringify(value) }

export const selectionBatchService = {
  /** 获取服务端按当前管理员授权筛选的学院及新建权限。 */
  colleges(): Promise<BatchCollegeOption[]> { return request('/admin/selection-batches/colleges') },
  /** 获取授权学院可见的学年目录。 */
  academicYears(collegeId: number): Promise<AcademicYearOption[]> {
    return request(`/admin/selection-batches/academic-years?collegeId=${collegeId}`)
  },
  batches(collegeId: number): Promise<BatchSummary[]> {
    return request(`/admin/selection-batches?collegeId=${collegeId}`)
  },
  batch(batchId: number): Promise<BatchDetail> { return request(`/admin/selection-batches/${batchId}`) },
  /** 创建草稿属于命令；保留键用于网络超时后的安全重试。 */
  create(payload: Record<string, unknown>): Promise<BatchDetail> {
    const body = json(payload)
    return mutate(`POST /admin/selection-batches ${body}`, '/admin/selection-batches', { method: 'POST', body })
  },
  /** 更新草稿元数据使用 If-Match，版本变化时由服务端拒绝覆盖。 */
  update(batchId: number, version: number, payload: Record<string, unknown>): Promise<BatchDetail> {
    const body = json(payload)
    return request(`/admin/selection-batches/${batchId}`, {
      method: 'PATCH', headers: { 'If-Match': `"batch-${version}"` }, body,
    })
  },
  /** 排期是完整替换请求，调用者需传当前批次版本和全部启用阶段。 */
  saveSchedule(batchId: number, version: number, payload: Record<string, unknown>): Promise<BatchDetail> {
    const body = json(payload)
    return request(`/admin/selection-batches/${batchId}/schedule`, {
      method: 'PUT', headers: { 'If-Match': `"batch-${version}"` }, body,
    })
  },
  /** 发布命令带幂等键，避免响应丢失后重复占用学院/学年运行槽位。 */
  publish(batchId: number): Promise<BatchDetail> {
    return mutate(`POST /admin/selection-batches/${batchId}/publish`, `/admin/selection-batches/${batchId}/publish`, { method: 'POST' })
  },
  /** 启动命令带幂等键，确保重试不会重复写入生命周期操作。 */
  start(batchId: number): Promise<BatchDetail> {
    return mutate(`POST /admin/selection-batches/${batchId}/start`, `/admin/selection-batches/${batchId}/start`, { method: 'POST' })
  },
  /** 生命周期状态命令复用待重试幂等键；服务端按合法状态转换并记入批次审计。 */
  lifecycle(batchId: number, action: 'pause' | 'resume' | 'cancel' | 'archive' | 'unarchive'): Promise<BatchDetail> {
    const path = `/admin/selection-batches/${batchId}/${action}`
    return mutate(`POST ${path}`, path, { method: 'POST' })
  },
  extendRound(batchId: number, roundNo: number, version: number, newEndAt: string, reason: string): Promise<BatchDetail> {
    const path = `/admin/selection-batches/${batchId}/rounds/${roundNo}/extend`
    const body = json({ newEndAt, reason })
    return mutate(`POST ${path} ${body} v${version}`, path, {
      method: 'POST', headers: { 'If-Match': `"batch-${version}"` }, body,
    })
  },
  reopenRound(batchId: number, roundNo: number, version: number, newEndAt: string, reason: string): Promise<BatchDetail> {
    const path = `/admin/selection-batches/${batchId}/rounds/${roundNo}/reopen`
    const body = json({ newEndAt, reason })
    return mutate(`POST ${path} ${body} v${version}`, path, {
      method: 'POST', headers: { 'If-Match': `"batch-${version}"` }, body,
    })
  },
  quotas(batchId: number): Promise<TeacherQuota[]> { return request(`/admin/selection-batches/${batchId}/teachers`) },
  /** 统计使用填报开窗时冻结的名单分母；查询不接受会改变口径的筛选条件。 */
  statistics(batchId: number): Promise<BatchStatistics> { return request(`/admin/selection-batches/${batchId}/statistics`) },
  /** 每次名额设置携带当前强版本标签，并按批次/导师/上限/版本生成独立幂等签名。 */
  setQuota(batchId: number, row: TeacherQuota, quotaLimit: number): Promise<TeacherQuota> {
    const body = json({ quotaLimit })
    const path = `/admin/selection-batches/${batchId}/teachers/${row.teacherId}/quota`
    return mutate(`PUT ${path} ${body} v${row.rowVersion}`, path, {
      method: 'PUT', headers: { 'If-Match': `"quota-${row.rowVersion}"` }, body,
    })
  },
  /** 查询认证导师本人名额关联的批次，不在客户端拼接导师身份 ID。 */
  teacherBatches(): Promise<TeacherScopeBatch[]> { return request('/teachers/me/application-scopes/batches') },
  /** 获取本人一个批次的当前范围版本。 */
  scope(batchId: number): Promise<TeacherScope> { return request(`/teachers/me/batches/${batchId}/application-scope`) },
  /** 完整替换范围并用强 ETag 做乐观并发控制。 */
  saveScope(batchId: number, scope: TeacherScope, payload: Record<string, unknown>): Promise<TeacherScope> {
    const body = json(payload)
    return request(`/teachers/me/batches/${batchId}/application-scope`, {
      method: 'PUT', headers: { 'If-Match': `"scope-${scope.versionNo}"` }, body,
    })
  },
  /** 读取导师所属学院的启用专业目录，后端仍会在保存时校验每个专业 ID。 */
  async majors(collegeId: number): Promise<BatchMajor[]> {
    interface MajorOptionResponse {
      majorId: number; majorCode: string; majorName: string; active: boolean
    }
    interface MajorPageResponse { items: MajorOptionResponse[]; total: number }
    const pageSize = 100
    const firstPage = await request<MajorPageResponse>(
      `/majors?collegeId=${collegeId}&active=true&pageNo=1&pageSize=${pageSize}`,
    )
    const options = [...firstPage.items]
    const pageCount = Math.ceil(firstPage.total / pageSize)
    for (let pageNo = 2; pageNo <= pageCount; pageNo += 1) {
      const page = await request<MajorPageResponse>(
        `/majors?collegeId=${collegeId}&active=true&pageNo=${pageNo}&pageSize=${pageSize}`,
      )
      options.push(...page.items)
    }
    return options.map((major) => ({
      id: major.majorId,
      collegeId,
      majorCode: major.majorCode,
      name: major.majorName,
      active: major.active,
    }))
  },
}
