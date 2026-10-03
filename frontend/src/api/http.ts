import type { ApiResult } from '../types/api'

// 默认走同源 /api，由 Vite 开发代理转发；部署时也可用环境变量指定 API 根路径。
const apiBaseUrl = import.meta.env.VITE_API_BASE_URL ?? '/api'

/**
 * API 失败的前端异常类型。
 * 同时保留业务错误码、HTTP 状态码和响应 data，让页面能够区分凭证失败、越权、冲突等情形。
 */
export class ApiError extends Error {
  constructor(
    public readonly code: string,
    message: string,
    public readonly status: number,
    public readonly data: unknown,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

function readCookie(name: string): string | undefined {
  // document.cookie 只能读取未设置 HttpOnly 的 Cookie；服务端 Session Cookie 不会暴露给脚本。
  // Cookie 格式是以分号分隔的 name=value 项，读取到后对 value 做 URL 解码。
  const prefix = `${name}=`
  const cookie = document.cookie
    .split('; ')
    .find((item) => item.startsWith(prefix))

  return cookie ? decodeURIComponent(cookie.slice(prefix.length)) : undefined
}

/**
 * 发送一个符合项目 API 约定的请求，并返回统一 Result<T> 信封里的 data。
 *
 * <p>调用方只需要传路径和标准 Fetch 参数；本函数统一设置 JSON 请求头、服务端 Session Cookie、
 * CSRF 请求头，并把非 2xx JSON 响应转换成 ApiError。若请求成功，调用方拿到的是业务 data，
 * 而不是完整的 { code, message, data } 外层对象。</p>
 *
 * @param path 相对于 API 根路径的端点路径，例如 /auth/me
 * @param init Fetch 的方法、请求体和其他标准选项
 * @returns 服务端响应中的 data 字段
 */
export async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  // 复制调用方传入的 headers 后再补默认值，避免直接修改调用方持有的 Headers 对象。
  const headers = new Headers(init.headers)
  const method = (init.method ?? 'GET').toUpperCase()

  // 带 JSON 请求体时设置默认 Content-Type；FormData 让浏览器自行生成带 boundary 的类型。
  if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }

  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    // 对会改变服务端状态的方法附加 CSRF 值。浏览器自动发送 Cookie，客户端再把同一值放入头部，
    // 后端会比较两处令牌；GET/HEAD/OPTIONS 不附加该头，因为它们按约定不执行状态变更。
    const csrfToken = readCookie('XSRF-TOKEN')
    if (csrfToken) headers.set('X-XSRF-TOKEN', csrfToken)
  }

  const response = await fetch(`${apiBaseUrl}${path}`, {
    ...init,
    method,
    headers,
    // 允许浏览器自动附带服务端 Session Cookie；前端不读取 Cookie 中的 Session ID，也不自行存储它。
    credentials: 'include',
  })
  // API 契约要求成功和失败响应都是 JSON Result 信封；若后端返回非 JSON，解析错误会作为普通异常抛出。
  const result = (await response.json()) as ApiResult<T>

  if (!response.ok) {
    // 不吞掉 HTTP 状态：页面根据业务 code 显示更具体的提示，未知 code 仍可显示后端 message。
    throw new ApiError(result.code, result.message, response.status, result.data)
  }

  return result.data
}
