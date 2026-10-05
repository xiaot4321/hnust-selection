import { ApiError, request } from '../api/http'
import type { PageResult } from '../types/api'

/** 保存尚未确认结果的操作密钥，让网络中断后的同一请求安全重试。 */
const pendingIdempotencyKeys = new Map<string, string>()

function keyFor(signature: string): string {
  const existing = pendingIdempotencyKeys.get(signature)
  if (existing) return existing
  const created = crypto.randomUUID()
  pendingIdempotencyKeys.set(signature, created)
  return created
}

/** 成功请求释放密钥；明确 4xx 可安全重提，网络错误/5xx 保留原密钥供重试。 */
async function mutate<T>(signature: string, path: string, init: RequestInit): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('Idempotency-Key', keyFor(signature))
  try {
    const result = await request<T>(path, { ...init, headers })
    pendingIdempotencyKeys.delete(signature)
    return result
  } catch (error) {
    if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
      pendingIdempotencyKeys.delete(signature)
    }
    throw error
  }
}

/** 人员模块从后端返回的安全管理视图；密码和联系方式不会出现在列表对象中。 */
export interface PersonRecord {
  id: number
  accountId: number
  loginIdentifier: string
  identifier: string
  fullName: string
  collegeId: number
  collegeName: string
  majorCode: string | null
  majorName: string | null
  degreeType: string | null
  enrollmentYearCode: string | null
  classificationVersion: number | null
  profileReviewStatus: string | null
}

export interface CollegeOption { id: number; code: string; name: string }
export interface AcademicYearOption { id: number; yearCode: string; displayName: string }
export interface MajorRecord {
  id: number; collegeId: number; majorCode: string; name: string; active: boolean
  validFrom: string | null; validTo: string | null; rowVersion: number
}
export interface EligibilityRecord {
  id: number; academicYearId: number; yearCode: string; collegeId: number
  personType: 'STUDENT' | 'TEACHER'; personId: number; personIdentifier: string; personName: string
  status: 'ELIGIBLE' | 'INELIGIBLE'; evidenceType: string; evidenceReference: string | null
  sourceName: string | null; validFrom: string | null; validTo: string | null; changedBy: number
}
export interface CreatedPerson {
  personType: 'STUDENT' | 'TEACHER'; personId: number; accountId: number; loginIdentifier: string
  credential: { temporaryCredential: string | null; expiresAt: string | null; credentialShownNow: boolean }
}
export interface ImportRow {
  rowNumber: number; personIdentifier: string; status: string; errorCode: string | null
  errorMessage: string | null; personId: number | null; eligibilityId: number | null
  loginIdentifier: string | null; temporaryCredential: string | null
}
export interface ImportResult {
  importId: number; personType: 'STUDENT' | 'TEACHER'; status: string
  acceptedCount: number; rejectedCount: number; rows: ImportRow[] | null
}

/**
 * 集中管理人员模块的 HTTP 契约。
 *
 * <p>表单组件只提供业务字段，不拼接请求地址或自行保存操作密钥。服务层会按完整命令内容保存
 * 未确认操作的 UUID v4；网络错误时重复提交相同命令会复用该键。服务端仍会再次校验授权范围。</p>
 */
export const personnelManagementService = {
  colleges(): Promise<CollegeOption[]> { return request('/admin/personnel/colleges') },
  academicYears(collegeId: number): Promise<AcademicYearOption[]> {
    return request(`/admin/personnel/academic-years?collegeId=${collegeId}`)
  },
  majors(collegeId: number, activeOnly = false): Promise<MajorRecord[]> {
    return request(`/admin/majors?collegeId=${collegeId}&activeOnly=${activeOnly}`)
  },
  students(collegeId: number, pageNo = 1, identifier = ''): Promise<PageResult<PersonRecord>> {
    const query = new URLSearchParams({ collegeId: String(collegeId), pageNo: String(pageNo), pageSize: '20' })
    if (identifier.trim()) query.set('identifier', identifier.trim())
    return request(`/admin/students?${query}`)
  },
  teachers(collegeId: number, pageNo = 1, identifier = ''): Promise<PageResult<PersonRecord>> {
    const query = new URLSearchParams({ collegeId: String(collegeId), pageNo: String(pageNo), pageSize: '20' })
    if (identifier.trim()) query.set('identifier', identifier.trim())
    return request(`/admin/teachers?${query}`)
  },
  createStudent(payload: Record<string, unknown>): Promise<CreatedPerson> {
    const body = JSON.stringify(payload)
    return mutate(`POST /admin/students ${body}`, '/admin/students', { method: 'POST', body })
  },
  createTeacher(payload: Record<string, unknown>): Promise<CreatedPerson> {
    const body = JSON.stringify(payload)
    return mutate(`POST /admin/teachers ${body}`, '/admin/teachers', { method: 'POST', body })
  },
  createMajor(payload: Record<string, unknown>): Promise<MajorRecord> {
    const body = JSON.stringify(payload)
    return mutate(`POST /admin/majors ${body}`, '/admin/majors', { method: 'POST', body })
  },
  updateMajor(id: number, payload: Record<string, unknown>): Promise<MajorRecord> {
    const body = JSON.stringify(payload)
    return mutate(`PATCH /admin/majors/${id} ${body}`, `/admin/majors/${id}`, { method: 'PATCH', body })
  },
  eligibility(collegeId: number, academicYearId: number, personType: string, identifier = '', history = false): Promise<EligibilityRecord[]> {
    const query = new URLSearchParams({ collegeId: String(collegeId), academicYearId: String(academicYearId), personType, history: String(history) })
    if (identifier.trim()) query.set('identifier', identifier.trim())
    return request(`/admin/annual-eligibilities?${query}`)
  },
  setEligibility(payload: Record<string, unknown>): Promise<EligibilityRecord> {
    const body = JSON.stringify(payload)
    return mutate(`POST /admin/annual-eligibilities ${body}`, '/admin/annual-eligibilities', { method: 'POST', body })
  },
  async downloadTemplate(collegeId: number, personType: string): Promise<void> {
    const apiBase = import.meta.env.VITE_API_BASE_URL ?? '/api'
    const url = `${apiBase}/admin/personnel-imports/template?collegeId=${collegeId}&personType=${personType}`
    let response: Response
    try {
      response = await fetch(url, { credentials: 'include' })
    } catch (error) {
      if (error instanceof TypeError) {
        throw new ApiError('NETWORK_ERROR', '无法连接服务，请确认前端代理和后端服务已启动。', 0, null)
      }
      throw error
    }
    if (!response.ok) {
      // 下载端点返回文件而非统一 Result；出错时优先复用后端信封中的业务提示。
      let message = `模板下载失败（HTTP ${response.status}）。请检查后端服务和当前学院授权。`
      try {
        const errorResult = JSON.parse(await response.text()) as { message?: unknown }
        if (typeof errorResult.message === 'string' && errorResult.message.trim()) message = errorResult.message
      } catch {
        // 网关错误页可能不是 JSON；对外只显示稳定的简短提示。
      }
      throw new ApiError('TEMPLATE_DOWNLOAD_FAILED', message, response.status, null)
    }
    const blob = await response.blob()
    const anchor = document.createElement('a')
    anchor.href = URL.createObjectURL(blob)
    anchor.download = personType === 'STUDENT' ? 'student-personnel-template.csv' : 'teacher-personnel-template.csv'
    anchor.click()
    window.setTimeout(() => URL.revokeObjectURL(anchor.href), 1000)
  },
  async importPersonnel(collegeId: number, academicYearId: number, personType: string, file: File): Promise<ImportResult> {
    const body = new FormData()
    body.set('collegeId', String(collegeId))
    body.set('academicYearId', String(academicYearId))
    body.set('personType', personType)
    body.set('file', file)
    // 文件摘要纳入命令签名；同名同大小但内容不同的文件不会错误复用前一次操作键。
    const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer())
    const fingerprint = Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('')
    const signature = `POST /admin/personnel-imports ${collegeId}/${academicYearId}/${personType}/${fingerprint}`
    return mutate(signature, '/admin/personnel-imports', { method: 'POST', body })
  },
  importRows(importId: number): Promise<ImportRow[]> { return request(`/admin/personnel-imports/${importId}/rows`) },
}
