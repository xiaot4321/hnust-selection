import { request } from '../api/http'
import type {
  AdminAuthorizationCommandResult,
  AdminAuthorizationStatus,
  GrantAdminAuthorizationPayload,
  ManagedAdminAuthorization,
} from '../types/api'

/**
 * 管理员授权功能的 API 访问层。
 *
 * <p>这里集中维护端点路径、查询参数和幂等请求头；页面组件不直接拼 API URL，
 * 业务授权和范围判断仍完全由后端 Security 与 Service 负责。</p>
 */
export const adminAuthorizationService = {
  /** 查询目标管理员在指定学院范围内的授权当前状态或历史记录。 */
  list(
    targetAccountId: number,
    collegeId: number,
    status: AdminAuthorizationStatus,
  ): Promise<ManagedAdminAuthorization[]> {
    const query = new URLSearchParams({
      collegeId: String(collegeId),
      status,
    })
    return request<ManagedAdminAuthorization[]>(
      `/admin/admin-accounts/${targetAccountId}/authorizations?${query.toString()}`,
    )
  },

  /** 为目标管理员提交一个普通业务能力授权命令。 */
  grant(
    targetAccountId: number,
    payload: GrantAdminAuthorizationPayload,
    idempotencyKey: string,
  ): Promise<AdminAuthorizationCommandResult> {
    return request<AdminAuthorizationCommandResult>(
      `/admin/admin-accounts/${targetAccountId}/authorizations`,
      {
        method: 'POST',
        headers: { 'Idempotency-Key': idempotencyKey },
        body: JSON.stringify(payload),
      },
    )
  },

  /** 撤销目标管理员指定的历史授权；服务端会将理由写入审计记录。 */
  revoke(
    targetAccountId: number,
    authorizationId: number,
    reason: string,
    idempotencyKey: string,
  ): Promise<AdminAuthorizationCommandResult> {
    return request<AdminAuthorizationCommandResult>(
      `/admin/admin-accounts/${targetAccountId}/authorizations/${authorizationId}/revoke`,
      {
        method: 'POST',
        headers: { 'Idempotency-Key': idempotencyKey },
        body: JSON.stringify({ reason }),
      },
    )
  },
}
