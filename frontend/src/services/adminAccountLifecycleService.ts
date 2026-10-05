import { request } from '../api/http'
import type { AdminAccountCredentialResult, AdminAccountDirectoryItem, PageResult } from '../types/api'

/** 总管理员账号生命周期 API 访问层。 */
export const adminAccountLifecycleService = {
  /** 分页查询管理员账号目录，不读取凭证或人员资料。 */
  list(pageNo = 1, pageSize = 20): Promise<PageResult<AdminAccountDirectoryItem>> {
    const query = new URLSearchParams({ pageNo: String(pageNo), pageSize: String(pageSize) })
    return request<PageResult<AdminAccountDirectoryItem>>(`/admin/admin-accounts?${query.toString()}`)
  },

  /** 创建普通管理员；业务能力由现有授权 API 单独配置。 */
  create(loginIdentifier: string, idempotencyKey: string): Promise<AdminAccountCredentialResult> {
    return request<AdminAccountCredentialResult>('/admin/admin-accounts', {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify({ loginIdentifier }),
    })
  },

  /** 给其他普通管理员重置一次性临时凭证。 */
  resetTemporaryCredential(accountId: number, idempotencyKey: string): Promise<AdminAccountCredentialResult> {
    return request<AdminAccountCredentialResult>(
      `/admin/admin-accounts/${accountId}/temporary-credential-reset`,
      {
        method: 'POST',
        headers: { 'Idempotency-Key': idempotencyKey },
      },
    )
  },
}
