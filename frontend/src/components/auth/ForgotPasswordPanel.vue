<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ApiError, request } from '../../api/http'
const emit = defineEmits<{ (event: 'close'): void }>()
const form = reactive({ loginIdentifier: '', email: '', code: '', newPassword: '', confirmPassword: '' })
const challengeId = ref('')
const busy = ref(false)
const error = ref('')
const sent = ref(false)
const complete = ref(false)
const cooldown = ref(0)
let timer: ReturnType<typeof setInterval> | undefined
watch(() => [form.loginIdentifier, form.email], () => { challengeId.value = ''; sent.value = false; form.code = '' })
function messageFor(cause: unknown) { return cause instanceof ApiError ? cause.message : '暂时无法连接服务，请稍后重试。' }
async function send() {
  if (busy.value || cooldown.value > 0) return
  busy.value = true; error.value = ''
  try {
    const receipt = await request<{ challengeId: string; resendAfterSeconds: number }>('/auth/recovery/code', {
      method: 'POST', body: JSON.stringify({ loginIdentifier: form.loginIdentifier, email: form.email }),
    })
    challengeId.value = receipt.challengeId; sent.value = true; cooldown.value = receipt.resendAfterSeconds
  } catch (cause) { error.value = messageFor(cause) }
  finally { busy.value = false }
}
async function reset() {
  if (busy.value) return
  if (form.newPassword !== form.confirmPassword) { error.value = '两次输入的新密码不一致。'; return }
  busy.value = true; error.value = ''
  try {
    await request<boolean>('/auth/recovery/reset', { method: 'POST', body: JSON.stringify({ loginIdentifier: form.loginIdentifier,
      email: form.email, challengeId: challengeId.value, code: form.code, newPassword: form.newPassword }) })
    form.newPassword = ''; form.confirmPassword = ''; form.code = ''; challengeId.value = ''; complete.value = true
  } catch (cause) { error.value = messageFor(cause) }
  finally { busy.value = false }
}
onMounted(() => { timer = setInterval(() => { if (cooldown.value > 0) cooldown.value-- }, 1000) })
onUnmounted(() => { clearInterval(timer) })
</script>
<template>
  <section class="auth-card login-card" aria-labelledby="forgot-password-title">
    <div class="card-kicker">账号安全</div>
    <h2 id="forgot-password-title">找回密码</h2>
    <template v-if="complete">
      <p role="status">密码已重置，请使用新密码重新登录。其他设备的旧登录状态也将失效。</p>
      <button class="primary-button" type="button" @click="emit('close')">返回登录</button>
    </template>
    <template v-else>
      <p class="card-description">学生和导师可使用已验证绑定的邮箱找回密码。尚未绑定邮箱或管理员账号，请联系管理员处理。</p>
      <form class="form-stack" @submit.prevent="challengeId ? reset() : send()">
        <label for="recovery-identifier">登录标识</label>
        <input id="recovery-identifier" v-model="form.loginIdentifier" autocomplete="username" maxlength="128" required :disabled="busy" />
        <label for="recovery-email">已绑定邮箱</label>
        <input id="recovery-email" v-model="form.email" type="email" autocomplete="email" maxlength="254" required :disabled="busy" />
        <p v-if="sent" class="field-hint" role="status">如果账号与已绑定邮箱匹配，验证码将发往该邮箱，有效期 5 分钟。未收到时请检查垃圾邮件，或确认账号和邮箱后重试。</p>
        <template v-if="challengeId">
          <label for="recovery-code">邮箱验证码</label>
          <input id="recovery-code" v-model="form.code" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{6}" minlength="6" maxlength="6" required />
          <label for="recovery-password">新密码</label>
          <input id="recovery-password" v-model="form.newPassword" type="password" autocomplete="new-password" maxlength="128" required />
          <label for="recovery-password-confirm">确认新密码</label>
          <input id="recovery-password-confirm" v-model="form.confirmPassword" type="password" autocomplete="new-password" maxlength="128" required />
          <p class="field-hint">新密码最多 72 个 UTF-8 字节。</p>
          <button class="primary-button" type="submit" :disabled="busy">{{ busy ? '正在重置…' : '验证并重置密码' }}</button>
          <button class="text-button" type="button" :disabled="busy || cooldown > 0" @click="send">{{ cooldown > 0 ? `${cooldown} 秒后可重新发送` : '重新发送验证码' }}</button>
        </template>
        <button v-else class="primary-button" type="submit" :disabled="busy || cooldown > 0">{{ busy ? '正在发送…' : cooldown > 0 ? `${cooldown} 秒后可发送` : '发送验证码' }}</button>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      </form>
      <button class="text-button" type="button" :disabled="busy" @click="emit('close')">返回登录</button>
    </template>
  </section>
</template>
