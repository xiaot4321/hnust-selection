/** 所有 API 共用的响应信封；request<T> 会将其中的 data 交给业务代码。 */
export interface ApiResult<T> {
  /** 稳定的业务结果码；当前成功码为 OK，失败码由后端异常映射决定。 */
  code: string
  /** 面向调用方的提示文本；页面可针对已知 code 使用本地化提示覆盖。 */
  message: string
  /** 接口业务数据；错误响应可能为空。 */
  data: T
}

/** 分页接口使用的通用结构，pageNo 与 pageSize 由接口契约定义。 */
export interface PageResult<T> {
  /** 当前页实际返回的记录。 */
  items: T[]
  /** 满足筛选条件的总记录数，不只是当前页数量。 */
  total: number
  /** 当前页码及单页大小，具体起始页约定由对应 API 文档确定。 */
  pageNo: number
  pageSize: number
}

// 只描述系统业务角色；管理员的数据范围通过 authorizations 单独表达。
export type AccountRole = 'STUDENT' | 'TEACHER' | 'ADMIN'
/** 服务端允许总管理员授予普通管理员的学院/批次级能力目录。 */
export type AdminCapability = 'COLLEGE_ADMIN' | 'BATCH_MANAGER' | 'BATCH_AUDIT'

// /auth/me 中一条仍有效的管理员能力授权，不包含授权依据、签发人或撤销历史。
export interface AuthAuthorization {
  /** 用于细粒度权限判断的能力代码。 */
  capabilityCode: string
  /** 该能力所属的学院范围。 */
  collegeId: number
  /** null 表示学院内不限定批次；非 null 表示只能在指定批次范围内使用。 */
  batchId: number | null
}

/** 总管理员授权管理与人员管理页面可选择的启用学院最小信息。 */
export interface CollegeOption {
  id: number
  code: string
  name: string
}

/** 总管理员授权管理接口返回的授权历史记录；status 由 revokedAt 推导。 */
export type AdminAuthorizationStatus = 'ACTIVE' | 'REVOKED' | 'ALL'

/** 当前授权目录中可以创建的业务能力及其学院/可选批次范围。 */
export interface GrantAdminAuthorizationPayload {
  /** 普通管理员业务能力代码；不含保留的 ADMIN_ACCOUNT_MANAGER。 */
  capabilityCode: AdminCapability
  /** 由总管理员选定并经服务端范围校验的学院主键。 */
  collegeId: number
  /** 可选批次主键；省略时表示整个学院范围。 */
  batchId: number | null
  /** 业务方审批或授权依据。 */
  basis: string
}

/** 管理员授权列表的状态筛选；ALL 仅用于查询，不是授权记录持久状态。 */
export interface ManagedAdminAuthorization {
  /** account_authorization 主键，用于定位撤销命令。 */
  authorizationId: number
  /** 服务端能力目录中的业务能力编码。 */
  capabilityCode: string
  /** 授权所属学院主键。 */
  collegeId: number
  /** null 表示整个学院范围；非 null 时只包含此批次。 */
  batchId: number | null
  /** 授权依据，供总管理员复核。 */
  basis: string
  /** 执行授予操作的账号主键。 */
  grantedBy: number
  /** 授予时间，ISO-8601 UTC。 */
  grantedAt: string
  /** 当前是否有效或已经撤销。 */
  status: 'ACTIVE' | 'REVOKED'
  /** 执行撤销操作的账号主键；有效授权时为空。 */
  revokedBy: number | null
  /** 撤销时间，ISO-8601 UTC；有效授权时为空。 */
  revokedAt: string | null
  /** 撤销理由，来源于不可覆盖的审计事件；有效授权时为空。 */
  revocationReason: string | null
}

/** 授予/撤销命令的回执；授权 ID 可用于后续查询或撤销。 */
export interface AdminAuthorizationCommandResult {
  /** 新建或被撤销的授权记录主键。 */
  authorizationId: number
  /** 本次命令已完成的动作。 */
  result: 'GRANTED' | 'REVOKED'
}

/** 管理员账号创建/临时凭证重置回执；明文只由首次响应短暂返回。 */
export interface AdminAccountCredentialResult {
  /** 新建或重置的管理员账号主键。 */
  accountId: number
  /** 管理员登录标识。 */
  loginIdentifier: string
  /** 当前请求完成的动作。 */
  result: 'CREATED' | 'RESET'
  /** 首次完成响应中的一次性凭证；幂等重放时为 null。 */
  temporaryCredential: string | null
  /** 兼容字段；当前凭证不设到期时间，因此通常为 null。 */
  expiresAt: string | null
  /** true 表示当前响应正在作本次唯一展示。 */
  credentialShownNow: boolean
}

/** 总管理员管理员账号目录条目；只暴露账号识别与生命周期状态字段。 */
export interface AdminAccountDirectoryItem {
  accountId: number
  loginIdentifier: string
  accountStatus: string
  mustChangePassword: boolean
  createdAt: string
}

/** 学生或导师本人身份概要；管理员账号没有关联人员记录时为 null。 */
export interface AuthIdentity {
  /** student 或 teacher 表的主键，不是账号主键。 */
  id: number
  /** 人员显示姓名。 */
  displayName: string
  /** 学生为学号，导师为工号。 */
  identifier: string
  /** 人员所属学院主键。 */
  collegeId: number
}

/** /auth/me 和登录响应中提供的当前账号视图，不包含密码或会话凭证。 */
export interface AuthUser {
  /** account 表主键，仅供界面识别当前账号；业务写请求不应由客户端提交此值。 */
  accountId: number
  /** 当前账号的登录标识。 */
  loginIdentifier: string
  /** 后端确认的系统业务角色。 */
  role: AccountRole
  /** 当前账号状态；停用账号不会继续保持可用会话。 */
  accountStatus: string
  /** 为 true 时，前端只展示首次改密流程，后端也会限制其他业务 API。 */
  mustChangePassword: boolean
  /** 当前人员身份的最小概要；管理员通常为空。 */
  identity: AuthIdentity | null
  /** 管理员当前有效能力及其范围摘要；学生和导师一般为空列表。 */
  authorizations: AuthAuthorization[]
}

// 登录成功后返回的身份摘要；Session 凭证仍只通过 Cookie 保存。
export interface AuthSession {
  /** 登录成功标记；Session ID 仍由 HttpOnly Cookie 传输，不属于该 JSON 对象。 */
  authenticated: boolean
  /** 服务端认证并生成的用户摘要。 */
  user: AuthUser
}

export interface PasswordChangeResult {
  /** 服务端是否已完成密码更新。 */
  changed: boolean
  /** 更新后的首次改密状态；成功设置正式密码后应为 false。 */
  mustChangePassword: boolean
}
