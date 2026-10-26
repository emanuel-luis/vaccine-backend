import createClient, { type Middleware } from 'openapi-fetch'
import { useMemo } from 'react'
import { useAuth } from 'react-oidc-context'
import { useConfig } from '../config'
import type { paths } from './schema'

export class ApiError extends Error {
  readonly status: number
  /** Problem-details `code` and `field` (422 validation), when the body carries them. */
  readonly code?: string
  readonly field?: string
  constructor(status: number, message?: string, problem?: { code?: string; field?: string }) {
    super(message ?? `HTTP ${status}`)
    this.status = status
    this.code = problem?.code
    this.field = problem?.field
  }
}

export function createApi(baseUrl: string, getToken: () => string | undefined) {
  const client = createClient<paths>({ baseUrl })
  const bearer: Middleware = {
    onRequest({ request }) {
      const token = getToken()
      if (token) request.headers.set('Authorization', `Bearer ${token}`)
      return request
    },
  }
  client.use(bearer)
  return client
}

export type Api = ReturnType<typeof createApi>

/** Unwraps an openapi-fetch result: returns data or throws ApiError (carries the HTTP status). */
export function unwrap<T>(result: { data?: T; error?: unknown; response: Response }): T {
  if (result.data === undefined) throw apiErrorOf(result)
  return result.data
}

export function apiErrorOf(result: { error?: unknown; response: Response }): ApiError {
  const e = result.error
  const p = typeof e === 'object' && e !== null ? (e as Record<string, unknown>) : {}
  return new ApiError(result.response.status, undefined, {
    code: typeof p.code === 'string' ? p.code : undefined,
    field: typeof p.field === 'string' ? p.field : undefined,
  })
}

/** Like unwrap, for envelopes whose generated `data` is optional: throws when it is missing. */
export function unwrapData<T>(result: { data?: { data?: T }; error?: unknown; response: Response }): T {
  const body = unwrap(result).data
  if (body === undefined) throw new ApiError(result.response.status, 'empty body')
  return body
}

/** Query retry: network errors and 5xx only, never 401/403 or other 4xx. */
export function shouldRetry(failureCount: number, error: unknown): boolean {
  if (failureCount >= 2) return false
  return !(error instanceof ApiError) || error.status >= 500
}

export function useApi(): Api {
  const { apiBaseUrl } = useConfig()
  const token = useAuth().user?.access_token
  return useMemo(() => createApi(apiBaseUrl, () => token), [apiBaseUrl, token])
}
