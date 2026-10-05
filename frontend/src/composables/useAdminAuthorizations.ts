import { reactive, ref } from 'vue'
import { ApiError } from '../api/http'
import { adminAuthorizationService } from '../services/adminAuthorizationService'
import type {
  AdminAuthorizationStatus,
  GrantAdminAuthorizationPayload,
  ManagedAdminAuthorization,
} from '../types/api'

/** 管理员授权面板使用的表单和状态。 */
export interface AdminAuthorizationForm {
  targetAccountId: string
  status: AdminAuthorizationStatus
  capabilityCode: GrantAdminAuthorizationPayload['capabilityCode']
  batchId: string
  basis: string
}

/**
 * 管理员授权页面的交互状态与命令流程。
 *
 * <p>这个 composable 负责输入校验、加载/提交状态、错误提示与幂等键复用；
 * HTTP 端点由 adminAuthorizationService 负责，角色和数据范围则由后端最终校验。</p>
 */
export function useAdminAuthorizations(canManage: () => boolean, authorizedCollegeId: () => number | null) {
  // 目标账号 ID 可由账号目录选择后带入，也保留手动填写，方便直接处理已知账号编号。
  const form = reactive<AdminAuthorizationForm>({
    targetAccountId: '',
    status: 'ACTIVE',
    capabilityCode: 'COLLEGE_ADMIN',
    batchId: '',
    basis: '',
  })
  const authorizations = ref<ManagedAdminAuthorization[]>([])
  const hasQueried = ref(false)
  const loading = ref(false)
  const submitting = ref(false)
  const errorMessage = ref('')
  const successMessage = ref('')
  // 每一条授权独立保存撤销理由；命令失败时输入内容仍保留，便于修正后重试。
  const revokeReasons = reactive<Record<string, string>>({})
  // 对网络失败或服务端错误保留相同请求的 UUID，让用户重试不会重复创建授权。
  const pendingIdempotencyKeys = reactive<Record<string, string>>({})

  /** 把数字输入严格解析为安全范围内的正整数。 */
  function positiveId(value: string): number | null {
    if (!/^\d+$/.test(value.trim())) return null
    const parsed = Number(value)
    return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null
  }

  /** 把稳定业务错误码转换为页面提示；未知业务错误仍显示服务端 message。 */
  function messageFor(error: unknown): string {
    if (!(error instanceof ApiError)) return '暂时无法连接服务，请稍后重试。'
    const knownMessages: Record<string, string> = {
      FORBIDDEN: '当前账号不能执行这项管理员授权操作。',
      SCOPE_FORBIDDEN: '当前账号无权访问此学院或批次范围。',
      NOT_FOUND: '未找到该管理员、学院、批次或授权记录。',
      INVALID_ARGUMENT: '请检查账号编号、批次编号和授权依据。',
      STATE_CONFLICT: '授权状态已变化，或相同能力与范围已经授权。请刷新列表后重试。',
      IDEMPOTENCY_KEY_REUSED: '该操作标识已用于另一份请求，请重新提交。',
    }
    return knownMessages[error.code] ?? error.message
  }

  /** 每条具体命令复用自身的键；命令内容变化时会生成另一条签名与新键。 */
  function idempotencyKeyFor(signature: string): string {
    if (!pendingIdempotencyKeys[signature]) {
      pendingIdempotencyKeys[signature] = window.crypto.randomUUID()
    }
    return pendingIdempotencyKeys[signature]
  }

  /** 明确的客户端 4xx 已被服务端拒绝，可释放该键；网络错误和 5xx 保留原键。 */
  function clearKnownFailedKey(signature: string, error: unknown): void {
    if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
      delete pendingIdempotencyKeys[signature]
    }
  }

  /** 按表单条件加载授权和历史；开始新查询时清除旧目标的数据，避免错认记录。 */
  async function loadAuthorizations(options: { preserveSuccess?: boolean; duringSubmit?: boolean } = {}): Promise<void> {
    if (!canManage() || loading.value || (submitting.value && !options.duringSubmit)) return
    const targetAccountId = positiveId(form.targetAccountId)
    const collegeId = authorizedCollegeId()
    if (!targetAccountId || !collegeId || !Number.isSafeInteger(collegeId) || collegeId <= 0) {
      errorMessage.value = '请选择有效的授权学院，并填写目标管理员账号编号。'
      return
    }

    loading.value = true
    authorizations.value = []
    hasQueried.value = false
    errorMessage.value = ''
    if (!options.preserveSuccess) successMessage.value = ''
    try {
      authorizations.value = await adminAuthorizationService.list(
        targetAccountId,
        collegeId,
        form.status,
      )
      hasQueried.value = true
    } catch (error) {
      errorMessage.value = messageFor(error)
    } finally {
      loading.value = false
    }
  }

  /** 按表单选择的服务端能力目录项创建授权，并在成功后刷新历史列表。 */
  async function grantSelectedCapability(): Promise<void> {
    if (!canManage() || submitting.value || loading.value) return
    const targetAccountId = positiveId(form.targetAccountId)
    const collegeId = authorizedCollegeId()
    const hasBatch = form.batchId.trim().length > 0
    const batchId = hasBatch ? positiveId(form.batchId) : null
    const basis = form.basis.trim()
    if (form.capabilityCode === 'COLLEGE_ADMIN' && hasBatch) {
      errorMessage.value = '学院业务管理能力必须覆盖整个学院，不能限制到单个批次。'
      return
    }
    if (!targetAccountId || !collegeId || !Number.isSafeInteger(collegeId) || collegeId <= 0
      || (hasBatch && !batchId) || !basis) {
      errorMessage.value = '请填写有效的目标账号、授权依据和可选批次编号，并选择授权学院。'
      return
    }

    const payload: GrantAdminAuthorizationPayload = {
      capabilityCode: form.capabilityCode,
      collegeId,
      batchId,
      basis,
    }
    const signature = `POST /admin/admin-accounts/${targetAccountId}/authorizations ${JSON.stringify(payload)}`
    submitting.value = true
    errorMessage.value = ''
    successMessage.value = ''
    try {
      const result = await adminAuthorizationService.grant(
        targetAccountId,
        payload,
        idempotencyKeyFor(signature),
      )
      delete pendingIdempotencyKeys[signature]
      form.basis = ''
      // 刷新失败仍会显示授权成功回执以及单独的刷新错误，不把已提交命令误报为失败。
      await loadAuthorizations({ preserveSuccess: true, duringSubmit: true })
      successMessage.value = `授权已保存（记录 ${result.authorizationId}）。`
    } catch (error) {
      clearKnownFailedKey(signature, error)
      errorMessage.value = messageFor(error)
    } finally {
      submitting.value = false
    }
  }

  /** 撤销普通业务能力；保留授权历史，刷新结果并显示服务端回执。 */
  async function revokeAuthorization(authorization: ManagedAdminAuthorization): Promise<void> {
    if (!canManage() || submitting.value || loading.value
      || authorization.capabilityCode === 'ADMIN_ACCOUNT_MANAGER') return
    const reason = (revokeReasons[String(authorization.authorizationId)] ?? '').trim()
    if (!reason) {
      errorMessage.value = '撤销前请填写原因。'
      return
    }
    const targetAccountId = positiveId(form.targetAccountId)
    if (!targetAccountId) {
      errorMessage.value = '请先查询目标管理员账号。'
      return
    }

    const signature = `POST /admin/admin-accounts/${targetAccountId}/authorizations/${authorization.authorizationId}/revoke ${JSON.stringify({ reason })}`
    submitting.value = true
    errorMessage.value = ''
    successMessage.value = ''
    try {
      const result = await adminAuthorizationService.revoke(
        targetAccountId,
        authorization.authorizationId,
        reason,
        idempotencyKeyFor(signature),
      )
      delete pendingIdempotencyKeys[signature]
      delete revokeReasons[String(authorization.authorizationId)]
      await loadAuthorizations({ preserveSuccess: true, duringSubmit: true })
      successMessage.value = `授权已撤销（记录 ${result.authorizationId}）。`
    } catch (error) {
      clearKnownFailedKey(signature, error)
      errorMessage.value = messageFor(error)
    } finally {
      submitting.value = false
    }
  }

  return {
    form,
    authorizations,
    hasQueried,
    loading,
    submitting,
    errorMessage,
    successMessage,
    revokeReasons,
    loadAuthorizations,
    grantSelectedCapability,
    revokeAuthorization,
  }
}
