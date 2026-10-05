<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import AdminAuthorizationPanel from '../components/admin/AdminAuthorizationPanel.vue'
import AdminAccountLifecyclePanel from '../components/admin/AdminAccountLifecyclePanel.vue'
import PersonnelManagementPanel from '../components/admin/PersonnelManagementPanel.vue'
import BatchManagementPanel from '../components/admin/BatchManagementPanel.vue'
import AdminGovernancePanel from '../components/admin/AdminGovernancePanel.vue'
import TeacherWorkspace from '../components/teacher/TeacherWorkspace.vue'
import StudentWorkspace from '../components/student/StudentWorkspace.vue'
import { ApiError, request } from '../api/http'
import type { AccountRole as AuthRole, AuthSession, AuthUser, CollegeOption, PasswordChangeResult } from '../types/api'

// 此组件负责认证界面的状态与交互，不在浏览器端判断某个业务 API 是否可访问。
// 请求是否允许执行始终由 Spring Security 和后端业务服务按最新账号/授权信息决定。
// 页面只缓存服务端返回的当前用户信息；认证凭证由 HttpOnly Session Cookie 管理。
const user = ref<AuthUser | null>(null)
// 首屏会先查询会话，因此在查询结束前显示加载状态，而不是误显示登录表单。
const loading = ref(true)
// 登录、修改密码和退出操作共用提交状态，避免用户重复触发写请求。
const submitting = ref(false)
// 控制登录后的改密表单是否展开。
const passwordChangeOpen = ref(false)
// 创建管理员或从目录中选择后，把账号编号同步到授权面板。
const authorizationTargetAccountId = ref('')
// 总管理员跨学院授权时，从服务端读取可用学院清单供选择器展示。
const adminColleges = ref<CollegeOption[]>([])
// 保存当前页面可见的错误提示；成功请求时清空旧错误。
const errorMessage = ref('')
// 表单对象采用响应式数据，让输入框值与脚本中的状态保持同步。
const loginForm = reactive({ loginIdentifier: '', password: '' })
const passwordForm = reactive({ currentCredential: '', newPassword: '', confirmPassword: '' })
// 总管理员仍属于 ADMIN 角色，但明确显示系统级管理员身份。
const isTotalAdmin = computed(() => user.value?.role === 'ADMIN'
  && user.value.authorizations.some((authorization) => authorization.capabilityCode === 'ADMIN_ACCOUNT_MANAGER'))
// 角色名称只用于页面展示，实际访问权限仍由后端接口校验。
const roleLabel = computed(() => isTotalAdmin.value ? '总管理员' : ({
  STUDENT: '学生',
  TEACHER: '导师',
  ADMIN: '管理员',
}[user.value?.role ?? 'STUDENT']))

// 仅根据服务端会话能力显示管理组件；实际权限仍由后端在每次请求时重新检查。
const canManageAdminAuthorizations = computed(() => isTotalAdmin.value)
// 总管理员拥有所有管理员端能力；普通管理员只按实际 COLLEGE_ADMIN 记录展示人员管理入口。
const canManagePersonnel = computed(() => isTotalAdmin.value || (user.value?.role === 'ADMIN'
  && user.value.authorizations.some((authorization) => authorization.capabilityCode === 'COLLEGE_ADMIN')))
const canManageBatches = computed(() => isTotalAdmin.value || (user.value?.role === 'ADMIN'
  && user.value.authorizations.some((authorization) => authorization.capabilityCode === 'BATCH_MANAGER')))
const canViewBatches = computed(() => isTotalAdmin.value || (user.value?.role === 'ADMIN'
  && user.value.authorizations.some((authorization) => ['BATCH_MANAGER', 'BATCH_AUDIT'].includes(authorization.capabilityCode))))
const canUseAdminGovernance = computed(() => canManagePersonnel.value || canManageBatches.value)
const canManageTeacherScope = computed(() => user.value?.role === 'TEACHER')
// 保留总管理员记录中的所属学院作为默认选项，其可操作范围由学院列表提供全系统选择。
const adminManagerCollegeId = computed(() => user.value?.authorizations
  .find((authorization) => authorization.capabilityCode === 'ADMIN_ACCOUNT_MANAGER')?.collegeId ?? null)

const workspace = computed(() => {
  // 按服务端返回的角色选择说明文案，方便用户识别当前入口。
  // 这些文案只是界面提示，不能作为后端权限判断或数据范围过滤的依据。
  const options: Record<AuthRole, { title: string; description: string; detail: string }> = {
    STUDENT: {
      title: '学生服务',
      description: '账号已验证。后续学生业务将基于当前登录身份读取本人资料和互选进度。',
      detail: '专业与学位类型由管理员维护，登录后仅可查看。',
    },
    TEACHER: {
      title: '导师工作台',
      description: '账号已验证。后续申请列表将限制为当前导师收到的申请。',
      detail: '可报范围、名额和申请决定均由服务端再次校验。',
    },
    ADMIN: {
      title: '管理工作台',
      description: isTotalAdmin.value
        ? '账号已验证。当前为系统级总管理员，可在全系统范围使用全部管理员业务能力。'
        : '账号已验证。管理操作仍须匹配当前获授学院、批次和具体业务能力。',
      detail: isTotalAdmin.value
        ? '可跨学院和批次管理业务、维护管理员账号及授权；业务状态校验与审计仍然生效。'
        : '普通管理员只能在已获授的学院或批次范围内处理业务。',
    },
  }
  return options[user.value?.role ?? 'STUDENT']
})

// 将后端稳定错误码转换成便于用户理解的提示，未知错误沿用服务端消息。
const authErrors: Record<string, string> = {
  INVALID_CREDENTIALS: '登录标识或密码不正确。',
  ACCOUNT_DISABLED: '该账号已停用，请联系管理员。',
  PASSWORD_CHANGE_REQUIRED: '请先完成首次密码修改。',
  TEMP_CREDENTIAL_EXPIRED: '临时凭证无效或已撤销，请联系管理员处理。',
  TEMP_CREDENTIAL_ALREADY_USED: '该临时凭证已经使用，请使用新密码登录。',
  FORBIDDEN: '当前账号无权执行此操作。',
}

function messageFor(error: unknown): string {
  // ApiError 携带服务端的稳定错误码；页面优先显示已知错误的友好提示。
  // 未登记的业务错误保留后端 message，非 ApiError 则通常是网络中断或响应无法解析。
  if (error instanceof ApiError) return authErrors[error.code] ?? error.message
  return '暂时无法连接服务，请稍后重试。'
}

/** 为总管理员加载全系统启用学院目录，让跨学院授权可由下拉选项完成。 */
async function loadAdminColleges(currentUser: AuthUser | null): Promise<void> {
  adminColleges.value = []
  const totalAdmin = currentUser?.role === 'ADMIN'
    && currentUser.authorizations.some((authorization) => authorization.capabilityCode === 'ADMIN_ACCOUNT_MANAGER')
  if (!totalAdmin) return
  try {
    adminColleges.value = await request<CollegeOption[]>('/admin/personnel/colleges')
  } catch (error) {
    // 目录加载失败不注销会话，但应显示错误，避免授权操作在空学院范围下静默失败。
    errorMessage.value = messageFor(error)
  }
}

/**
 * 页面打开时读取当前服务端会话。
 * 401 表示用户尚未登录，是正常的登录页状态；网络/服务错误则保留在界面上提示用户。
 */
async function loadSession(): Promise<void> {
  // 首屏载入与登出后都会查询服务端，以服务端会话状态为准。
  loading.value = true
  errorMessage.value = ''
  try {
    // 首屏访问 /auth/me 先由后端确认会话；Spring Security 也会准备后续写请求使用的 XSRF-TOKEN。
    user.value = await request<AuthUser>('/auth/me')
    await loadAdminColleges(user.value)
  } catch (error) {
    // 未登录属于正常状态，显示登录表单；其他错误应提示服务暂不可用。
    if (error instanceof ApiError && error.status === 401) {
      user.value = null
      adminColleges.value = []
    } else {
      errorMessage.value = messageFor(error)
    }
  } finally {
    loading.value = false
  }
}

/**
 * 提交登录标识和密码，并用服务端返回的用户摘要切换到已登录界面。
 * Session Cookie 由浏览器自动保存，组件不会把密码或 Session ID 放进 localStorage。
 */
async function login(): Promise<void> {
  // 脚本状态拦截重复提交，按钮禁用则提供对应的视觉反馈。
  if (submitting.value) return
  submitting.value = true
  errorMessage.value = ''
  try {
    // 角色与授权由服务端返回，不从表单或浏览器存储恢复。
    const session = await request<AuthSession>('/auth/login', {
      method: 'POST',
      body: JSON.stringify(loginForm),
    })
    user.value = session.user
    await loadAdminColleges(session.user)
    // 密码只用于本次提交；认证后的页面不再保留明文密码。
    loginForm.password = ''
  } catch (error) {
    errorMessage.value = messageFor(error)
  } finally {
    submitting.value = false
  }
}

/**
 * 修改当前登录账号的密码。
 * 确认密码一致性只在前端提供即时反馈；凭证校验、新密码长度和一次性凭证消费均由后端负责。
 */
async function changePassword(): Promise<void> {
  // 客户端先做一致性检查以便及时反馈，密码规则和凭证有效性仍由服务端最终判定。
  if (submitting.value) return
  if (passwordForm.newPassword !== passwordForm.confirmPassword) {
    errorMessage.value = '两次输入的新密码不一致。'
    return
  }
  submitting.value = true
  errorMessage.value = ''
  try {
    // 首次改密发送临时凭证，常规改密发送当前密码；两种情况使用同一已确认端点。
    // 当前正式密码或临时凭证都通过同一个 currentCredential 字段发送，服务端依据账号状态判别。
    await request<PasswordChangeResult>('/auth/password-change', {
      method: 'POST',
      body: JSON.stringify({
        currentCredential: passwordForm.currentCredential,
        newPassword: passwordForm.newPassword,
      }),
    })
    passwordForm.currentCredential = ''
    passwordForm.newPassword = ''
    passwordForm.confirmPassword = ''
    passwordChangeOpen.value = false
    // 改密后重新读取身份，让会话信息与服务端保持一致。
    user.value = await request<AuthUser>('/auth/me')
    await loadAdminColleges(user.value)
  } catch (error) {
    errorMessage.value = messageFor(error)
  } finally {
    submitting.value = false
  }
}

async function logout(): Promise<void> {
  // 先请求服务端销毁会话，成功后再清空本地身份状态。
  submitting.value = true
  errorMessage.value = ''
  try {
    await request<boolean>('/auth/logout', { method: 'POST' })
    user.value = null
    adminColleges.value = []
    // 登出会清除 CSRF Cookie；再次登录前重新 GET /me，让服务端生成新的 CSRF 值。
    try {
      await request<AuthUser>('/auth/me')
    } catch (error) {
      // 登出后 /me 返回 401 是预期结果；其他失败说明服务或网络仍有问题，交给外层展示。
      if (!(error instanceof ApiError) || error.status !== 401) throw error
    }
  } catch (error) {
    // 登出请求失败或后续探测发生非 401 错误时提示用户；内层已经消化预期的未登录 401。
    errorMessage.value = messageFor(error)
  } finally {
    submitting.value = false
  }
}

// 组件挂载后查询服务端会话，让首屏在查询期间稳定显示 loading 分支。
onMounted(loadSession)
</script>

<template>
  <div class="portal-layout">
    <!-- 左侧菜单承载页面内导航；功能入口随登录身份变化，权限判定仍由服务端完成。 -->
    <aside class="sidebar" aria-label="系统侧边导航">
      <a class="sidebar-brand" href="#page-home" aria-label="返回湖南科技大学师生互选系统首页">
        <span class="brand-monogram" aria-hidden="true">科</span>
        <span class="brand-copy">
          <strong>湖南科技大学</strong>
          <small>研究生教育服务</small>
        </span>
      </a>

      <div class="sidebar-divider" aria-hidden="true"></div>
      <nav class="side-navigation" aria-label="主要导航">
        <span class="navigation-caption">服务导航</span>
        <a class="side-link" href="#page-home"><span class="side-link-mark" aria-hidden="true"></span>系统首页</a>
        <a class="side-link" href="#account-area">
          <span class="side-link-mark" aria-hidden="true"></span>{{ user ? '我的工作台' : '账号登录' }}
        </a>
        <a class="side-link" href="#service-guide"><span class="side-link-mark" aria-hidden="true"></span>服务说明</a>

        <template v-if="canManageAdminAuthorizations">
          <span class="navigation-caption navigation-subheading">管理员管理</span>
          <a v-if="canManagePersonnel" class="side-link" href="#personnel-management"><span class="side-link-mark" aria-hidden="true"></span>人员与资格</a>
          <a class="side-link" href="#admin-account-management"><span class="side-link-mark" aria-hidden="true"></span>管理员账号</a>
          <a class="side-link" href="#admin-authorization-management"><span class="side-link-mark" aria-hidden="true"></span>业务授权</a>
        </template>
        <template v-if="canViewBatches">
          <span class="navigation-caption navigation-subheading">批次业务</span>
          <a class="side-link" href="#batch-management"><span class="side-link-mark" aria-hidden="true"></span>批次与导师名额</a>
        </template>
        <template v-if="canUseAdminGovernance">
          <span class="navigation-caption navigation-subheading">审核与纠错</span>
          <a class="side-link" href="#admin-governance"><span class="side-link-mark" aria-hidden="true"></span>审核与业务纠错</a>
        </template>
        <template v-if="canManageTeacherScope">
          <span class="navigation-caption navigation-subheading">导师工作台</span>
          <a class="side-link" href="#teacher-workspace"><span class="side-link-mark" aria-hidden="true"></span>申请与招生管理</a>
        </template>
      </nav>

      <div class="sidebar-bottom">
        <div v-if="user" class="sidebar-user">
          <span class="sidebar-avatar">{{ user.identity?.displayName?.slice(0, 1) ?? '管' }}</span>
          <span class="sidebar-user-copy">
            <strong>{{ user.identity?.displayName ?? user.loginIdentifier }}</strong>
            <small>{{ roleLabel }}</small>
          </span>
        </div>
        <p v-else class="sidebar-note">唯实惟新 · 至诚致志</p>
        <button v-if="user" class="sidebar-logout" type="button" :disabled="submitting" @click="logout">退出登录</button>
        <p v-else class="sidebar-security"><span class="secure-dot"></span>安全会话 · 校园服务</p>
      </div>
    </aside>

    <div class="portal-main">
      <header class="portal-topline">
        <div class="breadcrumb"><span>湖南科技大学</span><i>/</i><span>师生互选系统</span></div>
        <a v-if="!user && !loading" class="header-login-link" href="#account-area">进入系统 <span aria-hidden="true">→</span></a>
        <span v-else-if="user" class="header-role">当前身份：{{ roleLabel }}</span>
        <span v-else class="header-role">正在确认登录状态</span>
      </header>

      <!-- 标题区说明系统用途；互选步骤与已确认的业务流程保持一致。 -->
      <section id="page-home" class="portal-hero" aria-labelledby="page-title">
        <p class="hero-eyebrow">研究生教育 · 师生互选服务</p>
        <h1 id="page-title">师生互选系统</h1>
        <p class="hero-description">学生按志愿选择导师，导师分轮处理申请，录取后关系即时锁定。</p>
        <div class="hero-roles" aria-label="师生互选流程">
          <span><b>1</b> 学生填报志愿</span>
          <i aria-hidden="true"></i>
          <span><b>2</b> 导师逐项处理</span>
          <i aria-hidden="true"></i>
          <span><b>3</b> 录取关系锁定</span>
        </div>
      </section>

      <main class="content-area" :class="{ 'guest-content-area': !user && !loading }">
        <section id="account-area" class="account-section" aria-labelledby="account-section-title">
          <div class="section-heading">
            <div>
              <p class="section-eyebrow">用户中心</p>
              <h2 id="account-section-title">{{ user ? workspace.title : '账号登录' }}</h2>
            </div>
            <span v-if="user" class="section-role">{{ roleLabel }}</span>
          </div>

          <!-- 首屏先查 /auth/me：身份确定前显示加载状态，避免登录表单或工作台短暂闪现。 -->
          <section v-if="loading" class="auth-card loading-card" aria-live="polite">
            <span class="loader" aria-hidden="true"></span>
            <p>正在确认登录状态</p>
          </section>

          <!-- 新账号必须先完成改密；后端仍会在受保护接口上强制检查该状态。 -->
          <section v-else-if="user && user.mustChangePassword" class="auth-card" aria-labelledby="password-title">
            <div class="card-kicker">首次登录</div>
            <h2 id="password-title">设置正式密码</h2>
            <p class="card-description">首次凭证不设到期时间，但只能使用一次。学生/导师账号使用学号/工号末尾六位（编号不足六位时使用完整编号）；管理员使用创建或重置时收到的随机凭证。完成修改后，当前会话将继续保持登录。</p>
            <div class="account-chip">
              <span class="avatar-small">{{ user.identity?.displayName?.slice(0, 1) ?? '管' }}</span>
              <span>{{ user.identity?.displayName ?? user.loginIdentifier }}</span>
              <span class="role-tag">{{ roleLabel }}</span>
            </div>
            <form class="form-stack" @submit.prevent="changePassword">
              <label for="current-credential">初始密码或一次性凭证</label>
              <input id="current-credential" v-model="passwordForm.currentCredential" type="password" autocomplete="current-password" required />
              <label for="new-password">新密码</label>
              <input id="new-password" v-model="passwordForm.newPassword" type="password" autocomplete="new-password" maxlength="128" required />
              <label for="confirm-password">确认新密码</label>
              <input id="confirm-password" v-model="passwordForm.confirmPassword" type="password" autocomplete="new-password" maxlength="128" required />
              <p class="field-hint">新密码最多 72 个 UTF-8 字节，避免安全哈希截断。</p>
              <p v-if="errorMessage" class="form-error" role="alert">{{ errorMessage }}</p>
              <button class="primary-button" type="submit" :disabled="submitting">
                {{ submitting ? '正在保存…' : '保存并继续' }}
                <span aria-hidden="true">→</span>
              </button>
            </form>
          </section>

          <div v-else-if="user && user.role === 'STUDENT'" class="management-area student-management-area">
            <div class="welcome-topline student-account-tools">
              <div class="card-kicker">账号安全</div>
              <div class="account-actions">
                <button class="text-button" type="button" :disabled="submitting" @click="passwordChangeOpen = !passwordChangeOpen">
                  {{ passwordChangeOpen ? '收起密码设置' : '修改密码' }}
                </button>
              </div>
            </div>
            <form v-if="passwordChangeOpen" id="student-password-settings" class="form-stack change-password-form" @submit.prevent="changePassword">
              <label for="student-current-password">当前密码</label>
              <input id="student-current-password" v-model="passwordForm.currentCredential" type="password" autocomplete="current-password" required />
              <label for="student-new-password">新密码</label>
              <input id="student-new-password" v-model="passwordForm.newPassword" type="password" autocomplete="new-password" maxlength="128" required />
              <label for="student-confirm-password">确认新密码</label>
              <input id="student-confirm-password" v-model="passwordForm.confirmPassword" type="password" autocomplete="new-password" maxlength="128" required />
              <p class="field-hint">新密码最多 72 个 UTF-8 字节。</p>
              <p v-if="errorMessage" class="form-error" role="alert">{{ errorMessage }}</p>
              <button class="primary-button" type="submit" :disabled="submitting">{{ submitting ? '正在保存…' : '更新密码' }}</button>
            </form>
            <StudentWorkspace :user="user" />
          </div>

          <div v-else-if="user && user.role === 'TEACHER'" id="teacher-workspace" class="management-area teacher-management-area">
            <div class="welcome-topline student-account-tools">
              <div class="card-kicker">账号安全</div>
              <div class="account-actions">
                <button class="text-button" type="button" :disabled="submitting" @click="passwordChangeOpen = !passwordChangeOpen">
                  {{ passwordChangeOpen ? '收起密码设置' : '修改密码' }}
                </button>
              </div>
            </div>
            <form v-if="passwordChangeOpen" id="teacher-password-settings" class="form-stack change-password-form" @submit.prevent="changePassword">
              <label for="teacher-current-password">当前密码</label>
              <input id="teacher-current-password" v-model="passwordForm.currentCredential" type="password" autocomplete="current-password" required />
              <label for="teacher-new-password">新密码</label>
              <input id="teacher-new-password" v-model="passwordForm.newPassword" type="password" autocomplete="new-password" maxlength="128" required />
              <label for="teacher-confirm-password">确认新密码</label>
              <input id="teacher-confirm-password" v-model="passwordForm.confirmPassword" type="password" autocomplete="new-password" maxlength="128" required />
              <p class="field-hint">新密码最多 72 个 UTF-8 字节。</p>
              <p v-if="errorMessage" class="form-error" role="alert">{{ errorMessage }}</p>
              <button class="primary-button" type="submit" :disabled="submitting">{{ submitting ? '正在保存…' : '更新密码' }}</button>
            </form>
            <TeacherWorkspace />
          </div>

          <!-- 已登录工作台铺满主内容区；不同管理功能按纵向顺序展开，不再缩进窄卡片。 -->
          <section v-else-if="user" class="auth-card welcome-card" aria-labelledby="welcome-title">
            <div class="welcome-topline">
              <div class="card-kicker">登录成功</div>
              <div class="account-actions">
                <button class="text-button" type="button" :disabled="submitting" @click="passwordChangeOpen = !passwordChangeOpen">{{ passwordChangeOpen ? '收起密码设置' : '修改密码' }}</button>
              </div>
            </div>
            <h2 id="welcome-title">{{ user.identity?.displayName ?? '管理员' }}，欢迎回来</h2>
            <p class="card-description">{{ workspace.description }}</p>
            <div class="workspace-panel">
              <div class="workspace-icon" aria-hidden="true">{{ user.role === 'STUDENT' ? '学' : user.role === 'TEACHER' ? '师' : '管' }}</div>
              <div>
                <h3>{{ workspace.title }}</h3>
                <p>{{ workspace.detail }}</p>
              </div>
            </div>
            <div v-if="user.role === 'ADMIN'" class="scope-section">
              <div class="scope-heading">当前能力授权 <span>{{ isTotalAdmin ? '系统级' : user.authorizations.length }}</span></div>
              <p v-if="isTotalAdmin" class="empty-scope">
                自动拥有全部管理员业务能力和全系统学院/批次范围；仍须遵守业务状态与审计要求。
              </p>
              <div v-else-if="user.authorizations.length" class="scope-list">
                <div v-for="(authorization, index) in user.authorizations" :key="`${authorization.capabilityCode}-${authorization.collegeId}-${authorization.batchId}-${index}`" class="scope-row">
                  <span class="scope-code">{{ authorization.capabilityCode }}</span>
                  <span>学院 {{ authorization.collegeId }}<template v-if="authorization.batchId"> · 批次 {{ authorization.batchId }}</template></span>
                </div>
              </div>
              <p v-else class="empty-scope">此账号当前未配置业务授权范围。</p>
            </div>
            <div v-if="canManageAdminAuthorizations" id="admin-account-management" class="management-area">
              <AdminAccountLifecyclePanel
                :can-manage="canManageAdminAuthorizations"
                :current-account-id="user.accountId"
                @account-created="authorizationTargetAccountId = String($event)"
                @authorization-target-selected="authorizationTargetAccountId = String($event)"
              />
            </div>
            <!-- 管理授权由独立功能组件负责；认证页只根据当前身份决定是否展示。 -->
            <div v-if="canManageAdminAuthorizations" id="admin-authorization-management" class="management-area">
              <AdminAuthorizationPanel
                :can-manage="canManageAdminAuthorizations"
                :admin-manager-college-id="adminManagerCollegeId"
                :authorized-colleges="adminColleges"
                :initial-target-account-id="authorizationTargetAccountId"
              />
            </div>
            <!-- 人员管理由独立管理组件承载，认证页面只依据当前能力摘要显示入口。 -->
            <div v-if="canManagePersonnel" class="management-area">
              <PersonnelManagementPanel :can-manage="canManagePersonnel" />
            </div>
            <div v-if="canViewBatches" id="batch-management" class="management-area">
              <!-- BATCH_AUDIT 只展示批次与统计；任何写请求仍由后端检查 BATCH_MANAGER。 -->
              <BatchManagementPanel :can-manage="canManageBatches" />
            </div>
            <div v-if="canUseAdminGovernance" class="management-area">
              <AdminGovernancePanel :can-review-personnel="canManagePersonnel" :can-manage-batches="canManageBatches" />
            </div>
            <form v-if="passwordChangeOpen" id="password-settings" class="form-stack change-password-form" @submit.prevent="changePassword">
              <div class="scope-heading">账号安全</div>
              <label for="current-password">当前密码</label>
              <input id="current-password" v-model="passwordForm.currentCredential" type="password" autocomplete="current-password" required />
              <label for="replacement-password">新密码</label>
              <input id="replacement-password" v-model="passwordForm.newPassword" type="password" autocomplete="new-password" maxlength="128" required />
              <label for="replacement-confirmation">确认新密码</label>
              <input id="replacement-confirmation" v-model="passwordForm.confirmPassword" type="password" autocomplete="new-password" maxlength="128" required />
              <p class="field-hint">新密码最多 72 个 UTF-8 字节，避免安全哈希截断。</p>
              <p v-if="errorMessage" class="form-error" role="alert">{{ errorMessage }}</p>
              <button class="primary-button" type="submit" :disabled="submitting">{{ submitting ? '正在保存…' : '更新密码' }}<span aria-hidden="true">→</span></button>
            </form>
            <p v-if="errorMessage && !passwordChangeOpen" class="form-error" role="alert">{{ errorMessage }}</p>
          </section>

          <!-- 未登录时显示表单；表单只收集凭证，角色由服务端账号记录决定。 -->
          <section v-else class="auth-card login-card" aria-labelledby="login-title">
            <div class="card-kicker">校园统一服务入口</div>
            <h2 id="login-title">欢迎登录</h2>
            <p class="card-description">请输入管理员分配的登录标识和密码。</p>
            <form class="form-stack" @submit.prevent="login">
              <label for="login-identifier">登录标识</label>
              <input id="login-identifier" v-model="loginForm.loginIdentifier" type="text" autocomplete="username" placeholder="学号、工号或管理员账号" maxlength="128" required />
              <label for="login-password">密码</label>
              <input id="login-password" v-model="loginForm.password" type="password" autocomplete="current-password" placeholder="请输入密码" maxlength="128" required />
              <p v-if="errorMessage" class="form-error" role="alert">{{ errorMessage }}</p>
              <button class="primary-button" type="submit" :disabled="submitting">
                {{ submitting ? '正在验证…' : '登录系统' }}
                <span aria-hidden="true">→</span>
              </button>
            </form>
            <p class="help-line">无法登录？请联系所在学院管理员核对账号状态。</p>
          </section>
        </section>

        <section id="service-guide" class="service-guide" aria-labelledby="service-guide-title">
          <p class="section-eyebrow">办事指引</p>
          <h2 id="service-guide-title">登录与互选流程</h2>
          <p>使用已开通的校园账号登录。系统根据当前身份展示对应入口，账号状态、业务权限和数据范围均由服务端校验。</p>
        </section>
      </main>

      <footer class="page-footer">
        <span>湖南科技大学 · 研究生教育服务</span>
        <span>师生互选系统 · 身份与授权保护</span>
      </footer>
    </div>
  </div>
</template>
