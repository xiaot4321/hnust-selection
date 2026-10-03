<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ApiError, request } from './api/http'
import type { AccountRole as AuthRole, AuthSession, AuthUser, PasswordChangeResult } from './types/api'

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
// 保存当前页面可见的错误提示；成功请求时清空旧错误。
const errorMessage = ref('')
// 表单对象采用响应式数据，让输入框值与脚本中的状态保持同步。
const loginForm = reactive({ loginIdentifier: '', password: '' })
const passwordForm = reactive({ currentCredential: '', newPassword: '', confirmPassword: '' })

// 角色名称只用于页面展示，实际访问权限仍由后端接口校验。
const roleLabel = computed(() => ({
  STUDENT: '学生',
  TEACHER: '导师',
  ADMIN: '管理员',
}[user.value?.role ?? 'STUDENT']))

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
      description: '账号已验证。管理操作仍须匹配当前学院、批次和具体能力授权。',
      detail: '总管理员额外持有管理员账户管理能力，不会扩大其他业务范围。',
    },
  }
  return options[user.value?.role ?? 'STUDENT']
})

// 将后端稳定错误码转换成便于用户理解的提示，未知错误沿用服务端消息。
const authErrors: Record<string, string> = {
  INVALID_CREDENTIALS: '登录标识或密码不正确。',
  ACCOUNT_DISABLED: '该账号已停用，请联系管理员。',
  PASSWORD_CHANGE_REQUIRED: '请先完成首次密码修改。',
  TEMP_CREDENTIAL_EXPIRED: '临时凭证已过期或无效，请联系管理员重新发放。',
  TEMP_CREDENTIAL_ALREADY_USED: '该临时凭证已经使用，请使用新密码登录。',
  FORBIDDEN: '当前账号无权执行此操作。',
}

function messageFor(error: unknown): string {
  // ApiError 携带服务端的稳定错误码；页面优先显示已知错误的友好提示。
  // 未登记的业务错误保留后端 message，非 ApiError 则通常是网络中断或响应无法解析。
  if (error instanceof ApiError) return authErrors[error.code] ?? error.message
  return '暂时无法连接服务，请稍后重试。'
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
  } catch (error) {
    // 未登录属于正常状态，显示登录表单；其他错误应提示服务暂不可用。
    if (error instanceof ApiError && error.status === 401) {
      user.value = null
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
  <main class="auth-shell">
    <header class="brand-bar">
      <div class="brand-mark" aria-hidden="true">互</div>
      <div>
        <div class="brand-name">湖南科技大学</div>
        <div class="brand-unit">计算机学院 · 师生互选系统</div>
      </div>
      <span class="secure-label"><span class="secure-dot"></span>安全会话</span>
    </header>

    <section class="content-grid" aria-labelledby="page-title">
      <div class="intro-panel">
        <div class="eyebrow">FACULTY · STUDENT SELECTION</div>
        <h1 id="page-title">让双向选择<br /><span>清晰、有序、可信。</span></h1>
        <p class="intro-copy">使用学校分配的系统账号登录。系统依据账号角色和授权范围提供相应入口，所有业务请求都会在服务端重新校验。</p>
        <div class="role-strip" aria-label="支持的账号角色">
          <span>学生</span><i></i><span>导师</span><i></i><span>管理员</span>
        </div>
        <div class="privacy-note">
          <span class="privacy-icon" aria-hidden="true">✓</span>
          <span>账号使用服务端会话保护，敏感操作受角色、学院及批次范围约束。</span>
        </div>
      </div>

      <!-- 首屏先查 /auth/me：身份确定前显示加载卡片，避免登录表单或工作台短暂闪现。 -->
      <section v-if="loading" class="auth-card loading-card" aria-live="polite">
        <span class="loader" aria-hidden="true"></span>
        <p>正在确认登录状态</p>
      </section>

      <!-- 新账号必须先完成改密；后端仍会在受保护接口上强制检查该状态。 -->
      <section v-else-if="user && user.mustChangePassword" class="auth-card" aria-labelledby="password-title">
        <div class="card-kicker">首次登录</div>
        <h2 id="password-title">设置正式密码</h2>
        <p class="card-description">临时凭证仅可使用一次。完成修改后，当前会话将继续保持登录。</p>
        <div class="account-chip">
          <span class="avatar-small">{{ user.identity?.displayName?.slice(0, 1) ?? '管' }}</span>
          <span>{{ user.identity?.displayName ?? user.loginIdentifier }}</span>
          <span class="role-tag">{{ roleLabel }}</span>
        </div>
        <form class="form-stack" @submit.prevent="changePassword">
          <label for="current-credential">临时凭证</label>
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

      <!-- 已登录工作台展示服务端身份；管理员列表只是当前授权摘要，实际请求仍由后端再次校验。 -->
      <section v-else-if="user" class="auth-card welcome-card" aria-labelledby="welcome-title">
        <div class="welcome-topline">
          <div class="card-kicker">登录成功</div>
          <div class="account-actions">
            <button class="text-button" type="button" :disabled="submitting" @click="passwordChangeOpen = !passwordChangeOpen">修改密码</button>
            <button class="text-button" type="button" :disabled="submitting" @click="logout">退出登录</button>
          </div>
        </div>
        <div class="welcome-avatar">{{ user.identity?.displayName?.slice(0, 1) ?? '管' }}</div>
        <span class="role-tag large-tag">{{ roleLabel }}</span>
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
          <div class="scope-heading">当前能力授权 <span>{{ user.authorizations.length }}</span></div>
          <div v-if="user.authorizations.length" class="scope-list">
            <div v-for="(authorization, index) in user.authorizations" :key="`${authorization.capabilityCode}-${authorization.collegeId}-${authorization.batchId}-${index}`" class="scope-row">
              <span class="scope-code">{{ authorization.capabilityCode }}</span>
              <span>学院 {{ authorization.collegeId }}<template v-if="authorization.batchId"> · 批次 {{ authorization.batchId }}</template></span>
            </div>
          </div>
          <p v-else class="empty-scope">此账号当前未配置业务授权范围。</p>
        </div>
        <form v-if="passwordChangeOpen" class="form-stack change-password-form" @submit.prevent="changePassword">
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
      <section v-else class="auth-card" aria-labelledby="login-title">
        <div class="card-kicker">账号登录</div>
        <h2 id="login-title">欢迎回来</h2>
        <p class="card-description">请输入管理员分配的账号信息。</p>
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
        <p class="help-line">无法登录？请联系计算机学院管理员核对账号状态。</p>
      </section>
    </section>

    <footer class="page-footer">
      <span>湖南科技大学 · 计算机学院</span>
      <span>师生互选系统 <i>·</i> 身份与授权保护</span>
    </footer>
  </main>
</template>
