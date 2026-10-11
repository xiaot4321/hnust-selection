export const APP_ERROR_EVENT = 'hnust:app-error'

export interface AppErrorNotice {
  source: 'api' | 'runtime'
  title?: string
  code?: string
  status?: number
  message: string
  path?: string
  context?: string
  occurredAt: number
}

type AppErrorInput = Omit<AppErrorNotice, 'occurredAt'>

/** 将接口或运行时异常交给全局播报层，页面组件仍可保留原有的就地错误提示。 */
export function announceAppError(error: AppErrorInput): void {
  if (typeof window === 'undefined') return
  window.dispatchEvent(new CustomEvent<AppErrorNotice>(APP_ERROR_EVENT, {
    detail: { ...error, occurredAt: Date.now() },
  }))
}
