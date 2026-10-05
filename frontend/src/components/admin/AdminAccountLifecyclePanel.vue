<script setup lang="ts">
import { nextTick, reactive, ref } from 'vue'
import { ApiError } from '../../api/http'
import { adminAccountLifecycleService } from '../../services/adminAccountLifecycleService'
import type { AdminAccountCredentialResult, AdminAccountDirectoryItem, PageResult } from '../../types/api'

/** 父页面只在服务端概要包含总管理员能力时显示该组件；接口仍逐次校验权限。 */
const props = defineProps<{ canManage: boolean; currentAccountId: number }>()
const emit = defineEmits<{
  (event: 'account-created', accountId: number): void
  (event: 'authorization-target-selected', accountId: number): void
}>()
const createForm = reactive({ loginIdentifier: '' })
const resetForm = reactive({ accountId: '' })
const result = ref<AdminAccountCredentialResult | null>(null)
const submitting = ref(false)
const errorMessage = ref('')
const successMessage = ref('')
const pendingKeys = new Map<string, string>()
const directoryOpen = ref(false)
const directoryDialog = ref<HTMLDialogElement | null>(null)
const directoryLoading = ref(false)
const directoryError = ref('')
const directoryPage = ref<PageResult<AdminAccountDirectoryItem> | null>(null)
const directoryPageNo = ref(1)

function positiveId(value: string): number | null {
  if (!/^\d+$/.test(value.trim())) return null
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null
}

function messageFor(error: unknown): string {
  if (!(error instanceof ApiError)) return '暂时无法连接服务，请稍后重试。'
  const messages: Record<string, string> = {
    FORBIDDEN: '当前账号不能创建或重置该管理员账号。',
    SCOPE_FORBIDDEN: '当前账号没有管理员账户管理能力。',
    NOT_FOUND: '未找到该普通管理员账号。',
    INVALID_ARGUMENT: '请检查登录标识或账号编号。',
    STATE_CONFLICT: '登录标识已被使用，或账号状态已变化。',
    IDEMPOTENCY_KEY_REUSED: '该操作标识已用于另一项请求，请重新提交。',
    REQUEST_IN_PROGRESS: '相同操作仍在处理中，请稍后重试。',
  }
  return messages[error.code] ?? error.message
}

function idempotencyKey(signature: string): string {
  let key = pendingKeys.get(signature)
  if (!key) {
    key = window.crypto.randomUUID()
    pendingKeys.set(signature, key)
  }
  return key
}

function clearKeyAfterKnownRejection(signature: string, error: unknown): void {
  if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
    pendingKeys.delete(signature)
  }
}

function directoryMessageFor(error: unknown): string {
  if (!(error instanceof ApiError)) return '暂时无法加载管理员列表，请稍后重试。'
  if (error.code === 'FORBIDDEN' || error.code === 'SCOPE_FORBIDDEN') return '当前账号没有查看管理员列表的权限。'
  return error.message
}

async function loadDirectory(pageNo = directoryPageNo.value): Promise<void> {
  if (!props.canManage || directoryLoading.value) return
  directoryLoading.value = true
  directoryError.value = ''
  try {
    directoryPage.value = await adminAccountLifecycleService.list(pageNo, 20)
    directoryPageNo.value = pageNo
  } catch (error) {
    directoryError.value = directoryMessageFor(error)
  } finally {
    directoryLoading.value = false
  }
}

async function openDirectory(): Promise<void> {
  if (!props.canManage || directoryOpen.value) return
  directoryOpen.value = true
  await nextTick()
  directoryDialog.value?.showModal()
  await loadDirectory(1)
}

function closeDirectory(): void {
  if (directoryDialog.value?.open) directoryDialog.value.close()
  directoryOpen.value = false
}

function isCurrentAdmin(account: AdminAccountDirectoryItem): boolean {
  return account.accountId === props.currentAccountId
}

function selectForAuthorization(account: AdminAccountDirectoryItem): void {
  if (isCurrentAdmin(account)) return
  emit('authorization-target-selected', account.accountId)
  closeDirectory()
}

function selectForCredentialReset(account: AdminAccountDirectoryItem): void {
  if (isCurrentAdmin(account)) return
  resetForm.accountId = String(account.accountId)
  successMessage.value = `已选择 ${account.loginIdentifier}；请在“重置临时凭证”表单中确认后提交。`
  closeDirectory()
}

function accountStatusLabel(status: string): string {
  if (status === 'ACTIVE') return '正常'
  if (status === 'DISABLED') return '已停用'
  return status
}

function passwordStateLabel(account: AdminAccountDirectoryItem): string {
  return account.mustChangePassword ? '待首次改密' : '已设置密码'
}

function formatCreatedAt(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit',
  }).format(date)
}

function showResult(response: AdminAccountCredentialResult): void {
  result.value = response
  // 创建请求若因网络中断而重放，回执仍包含账号 ID，可直接对该账号重新签发临时凭证。
  if (response.result === 'CREATED') {
    resetForm.accountId = String(response.accountId)
    createForm.loginIdentifier = ''
    emit('account-created', response.accountId)
  }
  successMessage.value = response.credentialShownNow
    ? `${response.result === 'CREATED' ? '管理员账号已创建' : '临时凭证已重置'}；凭证仅在此响应展示一次。`
    : '该操作此前已完成，临时凭证不会再次展示。若未保存，请对该账号执行一次重置以签发新凭证。'
}

async function createAccount(): Promise<void> {
  if (!props.canManage || submitting.value) return
  const loginIdentifier = createForm.loginIdentifier.trim()
  if (!loginIdentifier || loginIdentifier.length > 128) {
    errorMessage.value = '请输入不超过 128 个字符的登录标识。'
    return
  }
  const signature = `CREATE ${loginIdentifier}`
  submitting.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    const response = await adminAccountLifecycleService.create(loginIdentifier, idempotencyKey(signature))
    pendingKeys.delete(signature)
    showResult(response)
  } catch (error) {
    clearKeyAfterKnownRejection(signature, error)
    errorMessage.value = messageFor(error)
  } finally {
    submitting.value = false
  }
}

async function resetCredential(): Promise<void> {
  if (!props.canManage || submitting.value) return
  const accountId = positiveId(resetForm.accountId)
  if (!accountId) {
    errorMessage.value = '请输入有效的普通管理员账号编号。'
    return
  }
  const signature = `RESET ${accountId}`
  submitting.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    const response = await adminAccountLifecycleService.resetTemporaryCredential(
      accountId,
      idempotencyKey(signature),
    )
    pendingKeys.delete(signature)
    showResult(response)
  } catch (error) {
    clearKeyAfterKnownRejection(signature, error)
    errorMessage.value = messageFor(error)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <section class="admin-account-lifecycle" aria-labelledby="admin-account-lifecycle-title">
    <div class="lifecycle-heading">
      <div>
        <div class="scope-heading">账号管理</div>
        <h3 id="admin-account-lifecycle-title">管理员账号管理</h3>
      </div>
      <div class="lifecycle-heading-actions">
        <button class="directory-open-button" type="button" :disabled="directoryLoading" @click="openDirectory">
          查看已有管理员
        </button>
        <span class="lifecycle-badge">总管理员</span>
      </div>
    </div>
    <p class="lifecycle-description">
      创建普通管理员后，再通过下方业务授权面板配置其学院或批次能力。临时凭证随机生成、不设到期时间，只能使用一次并须线下交付；首次登录后必须设置正式密码。
    </p>

    <form class="lifecycle-form" @submit.prevent="createAccount">
      <div class="scope-heading">创建普通管理员</div>
      <label for="new-admin-login">登录标识</label>
      <input
        id="new-admin-login"
        v-model="createForm.loginIdentifier"
        type="text"
        maxlength="128"
        autocomplete="off"
        placeholder="输入管理员登录标识"
        required
      />
      <button class="lifecycle-button" type="submit" :disabled="submitting">
        {{ submitting ? '正在处理…' : '创建并签发临时凭证' }}
      </button>
    </form>

    <form class="lifecycle-form reset-form" @submit.prevent="resetCredential">
      <div class="scope-heading">重置临时凭证</div>
      <label for="reset-admin-account">普通管理员账号编号</label>
      <input
        id="reset-admin-account"
        v-model="resetForm.accountId"
        inputmode="numeric"
        pattern="[0-9]*"
        type="text"
        placeholder="新建账号后可直接使用返回的编号"
        required
      />
      <button class="lifecycle-button secondary-lifecycle-button" type="submit" :disabled="submitting">
        {{ submitting ? '正在处理…' : '重置并签发新凭证' }}
      </button>
    </form>

    <p v-if="errorMessage" class="form-error" role="alert">{{ errorMessage }}</p>
    <p v-if="successMessage" class="form-success" role="status">{{ successMessage }}</p>

    <section v-if="result" class="credential-result" aria-live="polite">
      <div class="scope-heading">操作回执 · 账号 {{ result.accountId }}</div>
      <p class="credential-account">{{ result.loginIdentifier }}</p>
      <template v-if="result.temporaryCredential && result.credentialShownNow">
        <label class="credential-label" for="issued-admin-credential">一次性临时凭证</label>
        <input id="issued-admin-credential" :value="result.temporaryCredential" readonly @focus="$event.target instanceof HTMLInputElement && $event.target.select()" />
        <p class="credential-expiry">不设到期时间 · 首次登录后必须设置正式密码。</p>
        <p class="credential-warning">请立即复制并线下转达。关闭或刷新页面后无法找回明文；如未保存，请重新执行重置。</p>
      </template>
      <p v-else class="credential-warning">
        此操作的凭证已经预留一次性展示，不会再次返回明文。对该账号执行一次新的重置可签发另一份凭证。
      </p>
    </section>

    <dialog
      v-if="directoryOpen"
      ref="directoryDialog"
      class="admin-directory-dialog"
      aria-labelledby="admin-directory-title"
      @cancel.prevent="closeDirectory"
    >
      <div class="directory-heading">
        <div>
          <div class="scope-heading">账号管理</div>
          <h4 id="admin-directory-title">现有管理员</h4>
        </div>
        <button class="directory-close-button" type="button" aria-label="关闭管理员列表" @click="closeDirectory">×</button>
      </div>
      <p class="directory-description">共 {{ directoryPage?.total ?? 0 }} 个管理员账号。列表不显示密码或临时凭证。</p>
      <p v-if="directoryError" class="form-error" role="alert">{{ directoryError }}</p>
      <div v-if="directoryLoading" class="directory-state" role="status">正在加载管理员列表…</div>
      <div v-else-if="directoryPage && directoryPage.items.length === 0" class="directory-state">
        暂无管理员账号。
      </div>
      <div v-else-if="directoryPage" class="directory-list" aria-live="polite">
        <article v-for="account in directoryPage.items" :key="account.accountId" class="directory-account-card">
          <div class="directory-account-main">
            <strong>{{ account.loginIdentifier }}</strong>
            <span class="directory-current-tag" v-if="isCurrentAdmin(account)">当前总管理员</span>
            <span class="directory-status" :class="account.accountStatus === 'ACTIVE' ? 'directory-status-active' : 'directory-status-disabled'">
              {{ accountStatusLabel(account.accountStatus) }}
            </span>
          </div>
          <div class="directory-account-meta">
            <span>账号 {{ account.accountId }}</span>
            <span>{{ passwordStateLabel(account) }}</span>
            <span>创建于 {{ formatCreatedAt(account.createdAt) }}</span>
          </div>
          <div class="directory-actions">
            <span v-if="isCurrentAdmin(account)" class="directory-self-note">本人账号不允许常规重置或自我授权</span>
            <template v-else>
              <button class="directory-row-button" type="button" @click="selectForAuthorization(account)">选择为授权对象</button>
              <button class="directory-row-button directory-reset-button" type="button" @click="selectForCredentialReset(account)">选择重置凭证</button>
            </template>
          </div>
        </article>
      </div>
      <div class="directory-footer">
        <button class="directory-row-button" type="button" :disabled="directoryLoading" @click="loadDirectory()">刷新</button>
        <div class="directory-pagination" v-if="directoryPage">
          <button class="directory-row-button" type="button" :disabled="directoryLoading || directoryPageNo <= 1" @click="loadDirectory(directoryPageNo - 1)">上一页</button>
          <span>第 {{ directoryPage.pageNo }} 页 / {{ Math.max(1, Math.ceil(directoryPage.total / directoryPage.pageSize)) }} 页</span>
          <button class="directory-row-button" type="button" :disabled="directoryLoading || directoryPageNo * directoryPage.pageSize >= directoryPage.total" @click="loadDirectory(directoryPageNo + 1)">下一页</button>
        </div>
      </div>
    </dialog>
  </section>
</template>

<style scoped>
.admin-account-lifecycle {
  margin-top: 0;
  padding-top: 0;
  text-align: left;
}

.lifecycle-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 13px;
  border-bottom: 1px solid var(--hnust-line);
}

.lifecycle-heading h3 {
  margin: 7px 0 0;
  color: var(--hnust-blue-deep);
  font-size: 17px;
  font-weight: 700;
}

.lifecycle-badge {
  flex: 0 0 auto;
  padding: 5px 10px;
  border: 1px solid #cfe0e6;
  border-radius: 999px;
  background: var(--hnust-pale);
  color: var(--hnust-blue-dark);
  font-size: 10px;
  font-weight: 750;
}

.lifecycle-heading-actions {
  display: flex;
  align-items: center;
  gap: 9px;
}

.directory-open-button,
.directory-row-button,
.directory-close-button {
  border: 1px solid #c8dce3;
  border-radius: 4px;
  background: #fff;
  color: var(--hnust-blue-deep);
  cursor: pointer;
  font: inherit;
  font-size: 11px;
  font-weight: 700;
}

.directory-open-button {
  min-height: 32px;
  padding: 0 10px;
}

.directory-open-button:hover:not(:disabled),
.directory-row-button:hover:not(:disabled) {
  border-color: var(--hnust-blue);
  background: #edf3f5;
}

.lifecycle-description {
  max-width: 760px;
  margin: 12px 0 0;
  color: var(--hnust-muted);
  font-size: 12px;
  line-height: 1.8;
}

.lifecycle-form {
  display: grid;
  max-width: 680px;
  gap: 8px;
  margin-top: 14px;
  padding: 14px 15px 15px;
  border: 1px solid #dce7ea;
  border-radius: 5px;
  background: #f8fafb;
  text-align: left;
}

.reset-form,
.credential-result {
  max-width: 620px;
  margin-top: 12px;
}

.reset-form {
  border-top: 1px solid #dce7ea;
}

.admin-directory-dialog {
  width: min(700px, calc(100vw - 32px));
  max-height: min(82vh, 760px);
  padding: 20px;
  border: 1px solid #d5dedd;
  border-radius: 5px;
  box-shadow: 0 12px 34px rgba(39, 55, 60, 0.16);
  color: var(--hnust-ink);
}

.admin-directory-dialog::backdrop {
  background: rgba(39, 52, 56, 0.3);
}

.directory-heading,
.directory-account-main,
.directory-footer,
.directory-pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.directory-heading h4 {
  margin: 5px 0 0;
  color: var(--hnust-blue-deep);
  font-size: 17px;
}

.directory-close-button {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  font-size: 21px;
  line-height: 1;
}

.directory-description {
  margin: 10px 0 14px;
  color: var(--hnust-muted);
  font-size: 11px;
}

.directory-list {
  display: grid;
  max-height: min(54vh, 480px);
  gap: 8px;
  overflow: auto;
  padding-right: 3px;
}

.directory-account-card {
  padding: 12px;
  border: 1px solid #e2e8e7;
  border-radius: 5px;
  background: #fbfcfc;
  transition: border-color 140ms ease, background-color 140ms ease;
}

.directory-account-card:hover {
  border-color: #c9dce2;
  background: #f8fbfc;
}

.directory-account-main {
  justify-content: flex-start;
  flex-wrap: wrap;
}

.directory-account-main strong {
  color: var(--hnust-blue-deep);
  font-size: 13px;
}

.directory-current-tag,
.directory-status {
  padding: 3px 7px;
  border: 1px solid transparent;
  border-radius: 999px;
  font-size: 9px;
  font-weight: 750;
}

.directory-current-tag { border-color:#cfdee3; background:#e8f0f3; color:#466777; }
.directory-status-active { border-color:#d0e0d5; background:#e8f1eb; color:#4f705e; }
.directory-status-disabled { border-color:#ead8d8; background:#f3eaea; color:#865d5d; }

.directory-account-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 7px 15px;
  margin-top: 8px;
  color: var(--hnust-muted);
  font-size: 10px;
}

.directory-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  margin-top: 10px;
}

.directory-row-button {
  min-height: 31px;
  padding: 0 9px;
  border-radius: 4px;
}

.directory-reset-button { color: #766b55; border-color: #ddd8cd; }
.directory-self-note,
.directory-state { color: var(--hnust-muted); font-size: 10px; }
.directory-state { padding: 24px 10px; text-align: center; }

.directory-footer {
  margin-top: 12px;
  padding-top: 11px;
  border-top: 1px solid var(--hnust-line);
}

.directory-pagination {
  justify-content: flex-end;
  color: var(--hnust-muted);
  font-size: 10px;
}

@media (max-width: 540px) {
  .admin-directory-dialog { padding: 14px; }
  .lifecycle-heading-actions { align-items: flex-end; flex-direction: column-reverse; gap: 5px; }
  .directory-footer { align-items: flex-start; flex-direction: column; }
  .directory-pagination { width: 100%; justify-content: space-between; }
}

.lifecycle-form label,
.credential-label {
  margin-top: 7px;
  color: #536a73;
  font-size: 12px;
  font-weight: 700;
}

.lifecycle-form input,
.credential-result input {
  width: 100%;
  min-height: 43px;
  padding: 9px 12px;
  border: 1px solid #d3dfe3;
  border-radius: 4px;
  outline: none;
  background: #fff;
  color: var(--hnust-ink);
  font: inherit;
  font-size: 12px;
}

.lifecycle-form input:focus,
.credential-result input:focus {
  border-color: var(--hnust-blue);
  background: #fff;
  box-shadow: 0 0 0 3px rgba(82, 120, 138, 0.14);
}

.lifecycle-button {
  min-height: 42px;
  margin-top: 5px;
  padding: 0 14px;
  border: 1px solid var(--hnust-blue-dark);
  border-radius: 4px;
  background: var(--hnust-blue-dark);
  color: #fff;
  cursor: pointer;
  font-family: inherit;
  font-size: 12px;
  font-weight: 750;
}

.lifecycle-button:hover:not(:disabled) {
  border-color: #3f6374;
  background: #3f6374;
}

.lifecycle-button:disabled {
  cursor: wait;
  opacity: 0.65;
}

.secondary-lifecycle-button {
  border-color: #cbd7d8;
  background: #fff;
  color: var(--hnust-blue-deep);
}

.secondary-lifecycle-button:hover:not(:disabled) {
  border-color: var(--hnust-blue);
  background: #edf3f5;
}

.credential-result {
  border-radius: 4px;
  padding: 13px;
  border-left: 3px solid var(--hnust-blue);
  background: #edf3f5;
}

.credential-account,
.credential-expiry,
.credential-warning {
  margin: 7px 0 0;
  color: #62767d;
  font-size: 11px;
  line-height: 1.65;
  overflow-wrap: anywhere;
}

.credential-label {
  display: block;
  margin-top: 12px;
}

.credential-result input {
  margin-top: 7px;
  background: #fff;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
}

.credential-warning {
  padding: 10px 12px;
  border-radius: 4px;
  background: #f3f0e7;
  color: #766340;
}

.form-success {
  margin: 12px 0 0;
  color: var(--hnust-success);
  font-size: 11px;
  line-height: 1.6;
}
</style>
