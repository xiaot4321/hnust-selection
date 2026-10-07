<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { ApiError, request } from '../../api/http'
const email = ref('')
const boundEmail = ref<string | null>(null)
const configured = ref(false)
const currentPassword = ref('')
const code = ref('')
const challengeId = ref('')
const busy = ref(false)
const ready = ref(false)
const error = ref('')
const message = ref('')
const cooldown = ref(0)
let timer: ReturnType<typeof setInterval> | undefined
watch(email, () => { challengeId.value = ''; code.value = ''; message.value = '' })
function messageFor(cause: unknown) { return cause instanceof ApiError ? cause.message : '暂时无法连接服务。' }
async function load() {
  try {
    const status = await request<{ email: string | null; configured: boolean }>('/auth/email')
    boundEmail.value = status.email; email.value = status.email ?? ''; configured.value = status.configured; ready.value = true
  } catch (cause) { error.value = messageFor(cause) }
}
async function send() {
  if (busy.value || cooldown.value > 0) return
  if (!currentPassword.value) { error.value = '请填写当前登录密码，验证是本人绑定邮箱。'; return }
  busy.value = true; error.value = ''; message.value = ''
  try {
    const receipt = await request<{ challengeId: string; resendAfterSeconds: number }>('/auth/email/code', {
      method: 'POST', body: JSON.stringify({ email: email.value, currentPassword: currentPassword.value }),
    })
    challengeId.value = receipt.challengeId; cooldown.value = receipt.resendAfterSeconds; currentPassword.value = ''
    message.value = '验证码已发送，请查看邮箱。验证码 5 分钟内有效。'
  } catch (cause) { error.value = messageFor(cause) }
  finally { busy.value = false }
}
async function confirm() {
  if (busy.value) return
  busy.value = true; error.value = ''
  try {
    await request<boolean>('/auth/email/confirm', { method: 'POST', body: JSON.stringify({ challengeId: challengeId.value, code: code.value }) })
    boundEmail.value = email.value.trim().toLowerCase(); challengeId.value = ''; code.value = ''; currentPassword.value = ''
    message.value = '邮箱已验证并绑定，以后可以使用此邮箱找回密码。'
  } catch (cause) { error.value = messageFor(cause) }
  finally { busy.value = false }
}
onMounted(() => { void load(); timer = setInterval(() => { if (cooldown.value > 0) cooldown.value-- }, 1000) })
onUnmounted(() => { clearInterval(timer) })
</script>
<template>
  <section class="email-security-panel" aria-labelledby="email-security-title">
    <h3 id="email-security-title">邮箱与账号安全</h3>
    <p>{{ boundEmail ? `已验证邮箱：${boundEmail}` : '尚未绑定邮箱。请先验证邮箱，忘记密码时才能通过邮件找回。' }}</p>
    <p v-if="ready && !configured" class="form-error">邮件服务尚未配置，请联系管理员完成设置后再绑定。</p>
    <form v-if="ready && configured" class="form-stack" @submit.prevent="challengeId ? confirm() : send()">
      <label for="security-email">{{ boundEmail ? '更换绑定邮箱' : '绑定邮箱' }}</label>
      <input id="security-email" v-model="email" type="email" autocomplete="email" maxlength="254" required :disabled="busy" />
      <template v-if="!challengeId">
        <label for="email-current-password">当前登录密码</label>
        <input id="email-current-password" v-model="currentPassword" type="password" autocomplete="current-password" maxlength="128" required />
        <button class="primary-button" type="submit" :disabled="busy || cooldown > 0">{{ busy ? '正在发送…' : cooldown > 0 ? `${cooldown} 秒后可发送` : '发送绑定验证码' }}</button>
      </template>
      <template v-else>
        <label for="email-binding-code">邮箱验证码</label>
        <input id="email-binding-code" v-model="code" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{6}" minlength="6" maxlength="6" required />
        <button class="primary-button" type="submit" :disabled="busy">{{ busy ? '正在验证…' : '验证并绑定邮箱' }}</button>
        <button class="text-button" type="button" :disabled="busy || cooldown > 0" @click="challengeId = ''; code = ''">{{ cooldown > 0 ? `${cooldown} 秒后可重新获取` : '重新获取验证码' }}</button>
      </template>
    </form>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <p v-if="message" role="status">{{ message }}</p>
  </section>
</template>
<style scoped>
.email-security-panel { margin: 20px 0; padding: 20px; border: 1px solid var(--hnust-line); border-radius: 8px; background: #fff; }
.email-security-panel h3 { margin-top: 0; color: var(--hnust-blue-deep); }
.email-security-panel p { line-height: 1.7; overflow-wrap: anywhere; }
.email-security-panel form { max-width: 560px; }
</style>
