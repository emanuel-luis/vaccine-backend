import createClient, { type Middleware } from 'openapi-fetch'
import { useMemo } from 'react'
import { useAuth } from 'react-oidc-context'
import { useConfig } from '../config'
import type { paths } from './schema'

export class ApiError extends Error {
  readonly status: number
  constructor(status: number, message?: string) {
    super(message ?? `HTTP ${status}`)
    this.status = status
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
export function unwrap<T>(result: { data?: T; response: Response }): T {
  if (result.data === undefined) throw new ApiError(result.response.status)
  return result.data
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
