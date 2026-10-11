<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { APP_ERROR_EVENT, type AppErrorNotice } from '../utils/errorAnnouncer'

interface ErrorToast extends AppErrorNotice { id: number }

const notices = ref<ErrorToast[]>([])
const recentNotices = new Map<string, number>()
const timers = new Map<number, number>()
const remainingTime = new Map<number, number>()
const timerStartedAt = new Map<number, number>()
let nextId = 0
const AUTO_CLOSE_MS = 15000
const API_DEDUP_MS = 5000
const MAX_VISIBLE_NOTICES = 3

function titleFor(notice: AppErrorNotice): string {
  if (notice.title) return notice.title
  if (notice.source === 'runtime') return '页面遇到异常'
  if (notice.code === 'NETWORK_ERROR' || notice.status === 0) return '服务连接异常'
  if (notice.code === 'INVALID_RESPONSE') return '服务响应异常'
  if (notice.status === 401) return '登录验证未通过'
  if (notice.status === 403) return '当前账号无权执行此操作'
  if (notice.status === 404) return '记录不存在或当前不可见'
  if (notice.status === 409 || notice.status === 412) return '数据状态发生变化'
  if (notice.status === 413) return '上传文件超过限制'
  if (notice.status === 415) return '文件格式不受支持'
  if (notice.status === 429) return '操作过于频繁'
  if (notice.status && notice.status >= 500) return '服务暂时无法完成操作'
  if (notice.status === 400 || notice.status === 422) return '提交内容未通过校验'
  return '操作未完成'
}

function guidanceFor(notice: AppErrorNotice): string {
  if (notice.source === 'runtime') return '请重试当前操作；如果问题仍然出现，请刷新页面后再试。未保存的内容可能需要重新填写。'
  if (notice.code === 'NETWORK_ERROR' || notice.status === 0) return '请检查网络连接及服务状态。连接恢复后重新载入数据，再决定是否重试刚才的操作。'
  if (notice.code === 'INVALID_RESPONSE') return '服务或前端代理返回的数据格式异常。请检查服务状态，稍后重新载入页面。'
  if (notice.status === 400 || notice.status === 422) return '请检查必填项、格式和当前输入，再重新提交。'
  if (notice.status === 401) return '请核对登录标识和密码；如果登录状态已失效，请重新登录后再继续。'
  if (notice.status === 403) return '当前账号没有该操作所需的权限或数据范围。请联系有权限的管理员处理。'
  if (notice.status === 404) return '相关记录可能已删除或不在当前账号的可见范围内。请刷新当前列表后再查找。'
  if (notice.status === 409 || notice.status === 412) return '相关数据可能已被更新，或当前业务状态不再允许此操作。请刷新数据并核对后再试。'
  if (notice.status === 413) return '上传文件超过系统允许的大小，请缩小文件后重试。'
  if (notice.status === 415) return '文件格式不受支持，请选择页面说明中允许的文件类型。'
  if (notice.status === 429) return '操作过于频繁，请稍等片刻再重试。'
  if (notice.status && notice.status >= 500) return '服务端暂时发生异常，请稍后重试。若刚才执行的是保存、提交或审批，请先刷新并核对结果，避免重复操作。'
  return '请稍后重试；如果问题持续出现，请联系系统管理员并提供错误编号。'
}

function technicalInfo(notice: AppErrorNotice): string {
  return [notice.code, notice.status ? `HTTP ${notice.status}` : '', notice.path, notice.context]
    .filter((part): part is string => !!part)
    .join(' · ')
}

function formatTime(notice: AppErrorNotice): string {
  return new Date(notice.occurredAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

function dismiss(id: number): void {
  const timer = timers.get(id)
  if (timer !== undefined) window.clearTimeout(timer)
  timers.delete(id)
  remainingTime.delete(id)
  timerStartedAt.delete(id)
  notices.value = notices.value.filter((notice) => notice.id !== id)
}

function startTimer(id: number, duration: number): void {
  if (!notices.value.some((notice) => notice.id === id)) return
  remainingTime.set(id, duration)
  timerStartedAt.set(id, Date.now())
  timers.set(id, window.setTimeout(() => dismiss(id), duration))
}

function pauseTimer(id: number): void {
  const timer = timers.get(id)
  if (timer === undefined) return
  window.clearTimeout(timer)
  timers.delete(id)
  remainingTime.set(id, Math.max(0, (remainingTime.get(id) ?? AUTO_CLOSE_MS) - (Date.now() - (timerStartedAt.get(id) ?? Date.now()))))
}

function resumeTimer(id: number): void {
  if (timers.has(id)) return
  startTimer(id, remainingTime.get(id) ?? AUTO_CLOSE_MS)
}

function signatureFor(notice: AppErrorNotice): string {
  if (notice.source !== 'api') {
    return `${notice.code ?? ''}|${notice.status ?? ''}|${notice.path ?? notice.context ?? ''}|${notice.message}`
  }
  if (notice.code === 'NETWORK_ERROR' || notice.status === 0) return 'api|network'
  if (notice.code === 'INVALID_RESPONSE') return 'api|invalid-response'
  if (notice.status === 401) return 'api|session-expired'
  if (notice.status && notice.status >= 500) return `api|server|${notice.status}`
  return `api|${notice.code ?? ''}|${notice.status ?? ''}|${notice.path ?? ''}`
}

function enqueue(notice: AppErrorNotice): void {
  const signature = signatureFor(notice)
  const previousAt = recentNotices.get(signature) ?? 0
  const dedupWindow = notice.source === 'api' ? API_DEDUP_MS : 2500
  if (notice.occurredAt - previousAt < dedupWindow) return
  recentNotices.set(signature, notice.occurredAt)
  if (recentNotices.size > 40) {
    for (const [key, at] of recentNotices) if (notice.occurredAt - at > 15000) recentNotices.delete(key)
  }

  const toast: ErrorToast = { ...notice, id: ++nextId }
  if (notices.value.length >= MAX_VISIBLE_NOTICES) {
    dismiss(notices.value[notices.value.length - 1].id)
  }
  notices.value = [toast, ...notices.value]
  startTimer(toast.id, AUTO_CLOSE_MS)
}

function onAppError(event: Event): void {
  const notice = (event as CustomEvent<AppErrorNotice>).detail
  if (notice && typeof notice.message === 'string') enqueue(notice)
}

function onWindowError(event: Event): void {
  const errorEvent = event as ErrorEvent
  // 忽略图片等资源加载失败触发的普通 Event，只播报脚本运行异常。
  if (!errorEvent.message && !(event instanceof ErrorEvent)) return
  enqueue({
    source: 'runtime',
    code: 'JS_RUNTIME_ERROR',
    message: errorEvent.message || '页面脚本发生未处理异常。',
    context: errorEvent.filename ? `${errorEvent.filename.split('/').pop()}:${errorEvent.lineno || 0}` : undefined,
    occurredAt: Date.now(),
  })
}

function onUnhandledRejection(event: Event): void {
  const reason = (event as PromiseRejectionEvent).reason as { message?: unknown; code?: unknown; status?: unknown } | string | null
  const value = typeof reason === 'string' ? null : reason
  if (typeof value?.code === 'string' && typeof value.status === 'number') {
    // request() 的关键故障已播报；相同故障会被短时间去重。未捕获的普通 4xx 则仍需提醒用户。
    enqueue({
      source: 'api',
      code: value.code,
      status: value.status,
      message: typeof value.message === 'string' ? value.message : '请求未能完成。',
      occurredAt: Date.now(),
    })
    return
  }
  enqueue({
    source: 'runtime',
    code: typeof value?.code === 'string' ? value.code : 'UNHANDLED_PROMISE_REJECTION',
    status: typeof value?.status === 'number' ? value.status : undefined,
    message: typeof reason === 'string' ? reason : typeof value?.message === 'string' ? value.message : '异步操作发生未处理异常。',
    occurredAt: Date.now(),
  })
}

onMounted(() => {
  window.addEventListener(APP_ERROR_EVENT, onAppError)
  window.addEventListener('error', onWindowError)
  window.addEventListener('unhandledrejection', onUnhandledRejection)
})

onBeforeUnmount(() => {
  window.removeEventListener(APP_ERROR_EVENT, onAppError)
  window.removeEventListener('error', onWindowError)
  window.removeEventListener('unhandledrejection', onUnhandledRejection)
  for (const timer of timers.values()) window.clearTimeout(timer)
  timers.clear()
})
</script>

<template>
  <Teleport to="body">
    <div v-if="notices.length" class="error-toast-region" role="region" aria-label="系统异常通知" aria-live="assertive" aria-relevant="additions">
      <TransitionGroup name="error-toast" tag="div" class="error-toast-list">
        <article
          v-for="notice in notices"
          :key="notice.id"
          class="error-toast"
          role="alert"
          @mouseenter="pauseTimer(notice.id)"
          @mouseleave="resumeTimer(notice.id)"
        >
          <div class="error-toast-topline">
            <span class="error-toast-icon" aria-hidden="true">!</span>
            <div class="error-toast-heading">
              <p>系统异常 · {{ formatTime(notice) }}</p>
              <h2>{{ titleFor(notice) }}</h2>
            </div>
            <button class="error-toast-close" type="button" aria-label="关闭异常通知" @click="dismiss(notice.id)">×</button>
          </div>
          <p class="error-toast-message">{{ notice.message }}</p>
          <p class="error-toast-guidance"><strong>建议：</strong>{{ guidanceFor(notice) }}</p>
          <details v-if="technicalInfo(notice)" class="error-toast-details">
            <summary>查看错误编号</summary>
            <code>{{ technicalInfo(notice) }}</code>
          </details>
          <div class="error-toast-progress" aria-hidden="true"></div>
        </article>
      </TransitionGroup>
    </div>
  </Teleport>
</template>

<style scoped>
.error-toast-region { position:fixed; z-index:10000; top:18px; right:18px; width:min(430px, calc(100vw - 36px)); max-height:calc(100vh - 36px); overflow-y:auto; padding:2px; pointer-events:none; }
.error-toast-list { display:grid; gap:10px; }
.error-toast { position:relative; overflow:hidden; padding:15px 16px 14px; border:1px solid #d6e5ec; border-left:4px solid #a54848; border-radius:12px; background:#fff; box-shadow:0 10px 30px rgba(25,57,68,.16); color:#244255; pointer-events:auto; }
.error-toast-topline { display:flex; align-items:flex-start; gap:10px; padding-right:24px; }
.error-toast-icon { display:grid; width:25px; height:25px; flex:0 0 auto; place-items:center; border:1px solid #edcccc; border-radius:50%; background:#fbf0f0; color:#a54848; font-size:15px; font-weight:750; }
.error-toast-heading { min-width:0; flex:1; }
.error-toast-heading p { margin:0 0 3px; color:#8297a4; font-size:10px; }
.error-toast h2 { margin:0; color:#164d6a; font-size:14px; line-height:1.45; }
.error-toast-close { position:absolute; top:10px; right:10px; display:grid; width:28px; height:28px; place-items:center; border:1px solid transparent; border-radius:7px; background:transparent; color:#718695; cursor:pointer; font:inherit; font-size:20px; line-height:1; }
.error-toast-close:hover { border-color:#dbe8ee; background:#f3f8fb; color:#244255; }
.error-toast-message { margin:10px 0 0 35px; color:#405e70; font-size:12px; line-height:1.6; overflow-wrap:anywhere; }
.error-toast-guidance { margin:7px 0 0 35px; color:#5c7483; font-size:11px; line-height:1.55; }
.error-toast-guidance strong { color:#164d6a; }
.error-toast-details { margin:8px 0 0 35px; color:#68808f; font-size:10px; }
.error-toast-details summary { width:fit-content; cursor:pointer; }
.error-toast-details code { display:block; margin-top:6px; padding:7px 8px; border-radius:6px; background:#f4f7f9; color:#617582; font-family:ui-monospace,Consolas,monospace; overflow-wrap:anywhere; }
.error-toast-progress { position:absolute; right:0; bottom:0; left:0; height:2px; transform-origin:left; background:#d18b8b; animation:error-toast-countdown 15s linear forwards; }
.error-toast:hover .error-toast-progress { animation-play-state:paused; }
.error-toast-enter-active,.error-toast-leave-active,.error-toast-move { transition:opacity 180ms ease, transform 180ms ease; }
.error-toast-enter-from,.error-toast-leave-to { opacity:0; transform:translateX(18px); }
.error-toast-leave-active { position:absolute; right:0; left:0; }
.error-toast button:focus-visible,.error-toast summary:focus-visible { outline:3px solid rgba(7,143,202,.28); outline-offset:2px; }
@keyframes error-toast-countdown { to { transform:scaleX(0); } }
@media(max-width:520px) { .error-toast-region { top:10px; right:10px; width:calc(100vw - 20px); max-height:calc(100vh - 20px); } .error-toast { padding:13px 13px 12px; } }
@media(prefers-reduced-motion:reduce) { .error-toast-enter-active,.error-toast-leave-active,.error-toast-move { transition:none; } .error-toast-progress { animation:none; } }
</style>
