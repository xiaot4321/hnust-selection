<script setup lang="ts">
import { useAdminAuthorizations } from '../../composables/useAdminAuthorizations'
import type { CollegeOption, ManagedAdminAuthorization } from '../../types/api'
import { computed, ref, watch } from 'vue'

/** 父页面传入当前会话的能力摘要，只用于展示门槛；真实授权由服务端逐请求检查。 */
const props = defineProps<{
  canManage: boolean
  adminManagerCollegeId: number | null
  authorizedColleges: CollegeOption[]
  initialTargetAccountId?: string
}>()

// 默认选中总管理员所属学院；更换会话或目录后若默认学院不在列表中，则选第一所可用学院。
const selectedCollegeId = ref<number | null>(props.adminManagerCollegeId)
const selectedCollegeName = computed(() => props.authorizedColleges
  .find((college) => college.id === selectedCollegeId.value)?.name ?? '未加载')
watch(() => [props.authorizedColleges, props.adminManagerCollegeId] as const, ([options, preferred]) => {
  const selectedExists = options.some((college) => college.id === selectedCollegeId.value)
  if (!selectedExists) {
    selectedCollegeId.value = options.some((college) => college.id === preferred)
      ? preferred
      : (options[0]?.id ?? null)
  }
}, { immediate: true, deep: true })

// 查询、授予与撤销状态归属这个功能面板；HTTP 细节由 Service 层处理。
const {
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
} = useAdminAuthorizations(() => props.canManage, () => selectedCollegeId.value)

// 刚创建管理员后把服务端回执中的账号编号带入授权面板，操作者仍需主动查询并选择范围。
watch(() => props.initialTargetAccountId, (accountId) => {
  if (accountId) form.targetAccountId = accountId
}, { immediate: true })

/** 保留能力只能只读展示；常规授权接口不允许修改它。 */
function canRevoke(authorization: ManagedAdminAuthorization): boolean {
  return authorization.status === 'ACTIVE' && authorization.capabilityCode !== 'ADMIN_ACCOUNT_MANAGER'
}
</script>

<template>
  <!-- 此组件只负责管理员授权功能区，不承载认证、密码修改或其他角色页面。 -->
  <section class="admin-auth-panel" aria-labelledby="admin-auth-title">
    <div class="admin-auth-heading">
      <div>
        <div class="scope-heading">账号管理</div>
        <h3 id="admin-auth-title">管理员业务授权</h3>
      </div>
      <span class="admin-auth-badge">总管理员</span>
    </div>
    <p class="admin-auth-description">
      总管理员可为其他管理员配置任一学院的业务管理或批次审计范围。总管理员自身拥有全部管理员业务能力；其账号管理能力只能通过初始化或应急恢复流程调整。
    </p>

    <div class="admin-auth-scope" role="status">
      <span>当前选中学院</span>
      <strong>{{ selectedCollegeName }}</strong>
      <span class="scope-source">总管理员可跨学院管理</span>
    </div>

    <!-- 查询仍要求选定目标学院，避免把跨学院管理员身份误用成无范围的数据读取。 -->
    <form class="admin-auth-form" @submit.prevent="loadAuthorizations()">
      <label for="admin-auth-college">授权记录所属学院</label>
      <select id="admin-auth-college" v-model.number="selectedCollegeId" required>
        <option :value="null" disabled>请选择学院</option>
        <option v-for="college in authorizedColleges" :key="college.id" :value="college.id">
          {{ college.name }}（{{ college.code }}）
        </option>
      </select>

      <label for="admin-target-account">目标管理员账号编号</label>
      <input
        id="admin-target-account"
        v-model="form.targetAccountId"
        inputmode="numeric"
        pattern="[0-9]*"
        type="text"
        placeholder="例如 7301"
        required
      />

      <label for="admin-auth-status">授权记录状态</label>
      <select id="admin-auth-status" v-model="form.status">
        <option value="ACTIVE">当前有效</option>
        <option value="REVOKED">已撤销</option>
        <option value="ALL">全部记录</option>
      </select>
      <button class="secondary-button" type="submit" :disabled="loading || submitting">
        {{ loading ? '正在查询…' : '查询授权记录' }}
      </button>
    </form>

    <form class="admin-auth-form grant-admin-auth-form" @submit.prevent="grantSelectedCapability">
      <div class="scope-heading">授予业务能力</div>
      <div class="capability-summary" role="note">
        <span>能力范围</span>
        <strong>按所选学院或批次限定</strong>
        <span>管理员可获授人员管理、批次管理或批次审计；总管理员自身覆盖全系统</span>
      </div>
      <label for="grant-capability">业务能力</label>
      <!-- BATCH_MANAGER 可授予学院或单批次范围；COLLEGE_ADMIN 由后端强制保持学院级。 -->
      <select id="grant-capability" v-model="form.capabilityCode">
        <option value="COLLEGE_ADMIN">COLLEGE_ADMIN · 学院业务管理</option>
        <option value="BATCH_MANAGER">BATCH_MANAGER · 批次业务管理</option>
        <option value="BATCH_AUDIT">BATCH_AUDIT · 批次审计查询</option>
      </select>
      <label for="grant-batch-id">批次编号（可选）</label>
      <input
        id="grant-batch-id"
        v-model="form.batchId"
        inputmode="numeric"
        pattern="[0-9]*"
        type="text"
        placeholder="留空表示整个学院范围"
        :disabled="form.capabilityCode === 'COLLEGE_ADMIN'"
      />
      <p class="admin-auth-field-note">学院业务管理必须覆盖整个学院；批次管理和批次审计可进一步限定到单个批次。总管理员本人的权限不受该授权表单限制。</p>
      <label for="grant-basis">授权依据</label>
      <textarea
        id="grant-basis"
        v-model="form.basis"
        rows="2"
        placeholder="填写审批记录或授权依据"
        required
      ></textarea>
      <button class="secondary-button primary-secondary-button" type="submit" :disabled="submitting || loading">
        {{ submitting ? '正在提交…' : '保存业务授权' }}
      </button>
    </form>

    <p v-if="errorMessage" class="form-error" role="alert">{{ errorMessage }}</p>
    <p v-if="successMessage" class="form-success" role="status">{{ successMessage }}</p>

    <p v-if="!authorizations.length && !loading" class="empty-scope admin-empty-state">
      {{ hasQueried ? '该学院与状态条件下没有授权记录。' : '查询后会在这里显示授权范围、依据和操作历史。' }}
    </p>
    <div v-else class="managed-authorization-list" aria-live="polite">
      <article
        v-for="authorization in authorizations"
        :key="authorization.authorizationId"
        class="managed-authorization-card"
      >
        <div class="managed-authorization-topline">
          <span class="scope-code">{{ authorization.capabilityCode }}</span>
          <span
            class="authorization-status"
            :class="authorization.status === 'ACTIVE' ? 'status-active' : 'status-revoked'"
          >
            {{ authorization.status === 'ACTIVE' ? '有效' : '已撤销' }}
          </span>
        </div>
        <div class="managed-authorization-scope">
          学院 {{ authorization.collegeId }}
          <template v-if="authorization.batchId !== null"> · 批次 {{ authorization.batchId }}</template>
          <template v-else> · 全学院批次</template>
        </div>
        <p class="authorization-basis">依据：{{ authorization.basis }}</p>
        <p class="authorization-audit-line">授予账号 {{ authorization.grantedBy }} · {{ authorization.grantedAt }}</p>
        <p v-if="authorization.revokedAt" class="authorization-audit-line">
          撤销账号 {{ authorization.revokedBy }} · {{ authorization.revokedAt }}
        </p>
        <p v-if="authorization.revocationReason" class="authorization-basis">
          撤销原因：{{ authorization.revocationReason }}
        </p>

        <form
          v-if="canRevoke(authorization)"
          class="revoke-admin-auth-form"
          @submit.prevent="revokeAuthorization(authorization)"
        >
          <label :for="`revoke-reason-${authorization.authorizationId}`">撤销原因</label>
          <textarea
            :id="`revoke-reason-${authorization.authorizationId}`"
            v-model="revokeReasons[String(authorization.authorizationId)]"
            rows="2"
            placeholder="填写撤销原因"
            required
          ></textarea>
          <button class="text-button revoke-button" type="submit" :disabled="submitting || loading">
            撤销此授权
          </button>
        </form>
        <p v-else-if="authorization.capabilityCode === 'ADMIN_ACCOUNT_MANAGER'" class="reserved-authorization-note">
          保留能力：不能通过常规授权接口修改。
        </p>
      </article>
    </div>
  </section>
</template>

<style scoped>
.admin-auth-panel {
  margin-top: 0;
  padding-top: 0;
  text-align: left;
}

.admin-auth-heading,
.managed-authorization-topline {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.admin-auth-heading {
  padding-bottom: 13px;
  border-bottom: 1px solid var(--hnust-line);
}

.admin-auth-heading h3 {
  margin: 7px 0 0;
  color: var(--hnust-blue-deep);
  font-size: 17px;
  font-weight: 700;
}

.admin-auth-badge {
  flex: 0 0 auto;
  padding: 5px 8px;
  border: 1px solid #cfe0e6;
  border-radius: 999px;
  background: var(--hnust-pale);
  color: var(--hnust-blue-dark);
  font-size: 10px;
  font-weight: 750;
}

.admin-auth-description {
  max-width: 760px;
  margin: 12px 0 0;
  color: var(--hnust-muted);
  font-size: 12px;
  line-height: 1.8;
}

.admin-auth-scope {
  display: flex;
  align-items: baseline;
  gap: 7px;
  margin-top: 13px;
  padding: 9px 10px;
  border: 1px solid #d5e4e9;
  border-left: 3px solid var(--hnust-blue);
  border-radius: 4px;
  background: #edf4f6;
  color: var(--hnust-muted);
  font-size: 10px;
}

.admin-auth-scope strong {
  color: var(--hnust-blue-deep);
  font-size: 11px;
}

.admin-auth-scope .scope-source {
  margin-left: auto;
  color: var(--hnust-faint);
  font-size: 9px;
}

.admin-auth-form,
.revoke-admin-auth-form {
  display: grid;
  max-width: 620px;
  gap: 8px;
  margin-top: 16px;
  text-align: left;
}

.admin-auth-form label,
.revoke-admin-auth-form label {
  margin-top: 7px;
  color: #4e6671;
  font-size: 12px;
  font-weight: 700;
}

.admin-auth-form input,
.admin-auth-form select,
.admin-auth-form textarea,
.revoke-admin-auth-form textarea {
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
  transition: border-color 150ms ease, box-shadow 150ms ease, background 150ms ease;
}

.admin-auth-field-note {
  margin: -2px 0 2px;
  color: var(--hnust-faint);
  font-size: 9px;
  line-height: 1.55;
}

.capability-summary {
  display: grid;
  grid-template-columns: auto 1fr;
  align-items: baseline;
  gap: 4px 8px;
  padding: 9px 10px;
  border: 1px solid #dce7ea;
  border-radius: 4px;
  background: #f5f9fa;
  color: var(--hnust-muted);
  font-size: 9px;
}

.capability-summary strong {
  color: var(--hnust-blue-deep);
  font-size: 10px;
}

.capability-summary span:last-child {
  grid-column: 2;
  color: var(--hnust-faint);
}

.admin-auth-form textarea,
.revoke-admin-auth-form textarea {
  min-height: 58px;
  resize: vertical;
  line-height: 1.5;
}

.admin-auth-form input:focus,
.admin-auth-form select:focus,
.admin-auth-form textarea:focus,
.revoke-admin-auth-form textarea:focus {
  border-color: var(--hnust-blue);
  background: #fff;
  box-shadow: 0 0 0 3px rgba(82, 120, 138, 0.14);
}

.grant-admin-auth-form {
  margin-top: 21px;
  padding-top: 18px;
  border-top: 1px solid var(--hnust-line);
}

.secondary-button {
  min-height: 42px;
  margin-top: 5px;
  padding: 0 14px;
  border: 1px solid #c8dce3;
  border-radius: 4px;
  background: #fff;
  color: var(--hnust-blue-deep);
  cursor: pointer;
  font-family: inherit;
  font-size: 12px;
  font-weight: 750;
}

.secondary-button:hover:not(:disabled) {
  border-color: var(--hnust-blue);
  background: #edf3f5;
}

.secondary-button:disabled {
  cursor: wait;
  opacity: 0.65;
}

.primary-secondary-button {
  border-color: var(--hnust-blue-dark);
  background: var(--hnust-blue-dark);
  color: #fff;
}

.primary-secondary-button:hover:not(:disabled) {
  border-color: #3f6374;
  background: #3f6374;
}

.form-success {
  margin: 12px 0 0;
  color: var(--hnust-success);
  font-size: 10px;
  line-height: 1.6;
}

.admin-empty-state {
  margin: 16px 0 0;
  padding: 12px 14px;
  border-left: 3px solid var(--hnust-blue);
  border-radius: 4px;
  background: #edf4f6;
  line-height: 1.6;
}

.managed-authorization-list {
  display: flex;
  flex-direction: column;
  gap: 9px;
  margin-top: 13px;
}

.managed-authorization-card {
  max-width: 760px;
  padding: 13px 14px;
  border: 1px solid #dce6e9;
  border-radius: 5px;
  background: #fbfcfc;
}

.authorization-status {
  flex: 0 0 auto;
  padding: 3px 7px;
  border: 1px solid transparent;
  border-radius: 999px;
  font-size: 9px;
  font-weight: 750;
}

.status-active {
  border-color: #d0e0d5;
  background: #e8f1eb;
  color: var(--hnust-success);
}

.status-revoked {
  border-color: #ead8d8;
  background: #f3eaea;
  color: #865d5d;
}

.managed-authorization-scope,
.authorization-audit-line {
  margin-top: 7px;
  color: var(--hnust-muted);
  font-size: 11px;
  line-height: 1.55;
  overflow-wrap: anywhere;
}

.authorization-basis {
  margin: 7px 0 0;
  color: #4e6873;
  font-size: 11px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.authorization-audit-line {
  margin-top: 4px;
  color: var(--hnust-faint);
}

.revoke-admin-auth-form {
  margin-top: 11px;
  padding-top: 10px;
  border-top: 1px solid var(--hnust-line);
}

.revoke-button {
  justify-self: start;
  color: var(--hnust-danger);
}

.revoke-button:hover:not(:disabled) {
  color: #815b5b;
}

.reserved-authorization-note {
  margin: 10px 0 0;
  color: #766340;
  font-size: 9px;
  line-height: 1.6;
}
</style>
