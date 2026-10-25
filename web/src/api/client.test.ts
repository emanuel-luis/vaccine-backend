import { describe, expect, it, vi } from 'vitest'
import { ApiError, createApi, shouldRetry } from './client'

describe('createApi', () => {
  it('adds the bearer token to requests', async () => {
    const fetchMock = vi.fn<(req: Request) => Promise<Response>>(async () =>
      Response.json({ data: { username: 'x', roles: [], scope: { level: 'BR' } }, meta: {} }),
    )
    const api = createApi('http://api.test', () => 'tok')
    await api.GET('/api/v1/me', { fetch: fetchMock })
    const req = fetchMock.mock.calls[0]?.[0]
    expect(req?.headers.get('Authorization')).toBe('Bearer tok')
    expect(req?.url).toBe('http://api.test/api/v1/me')
  })

  it('sends no header without a token', async () => {
    const fetchMock = vi.fn<(req: Request) => Promise<Response>>(async () => Response.json({}))
    await createApi('http://api.test', () => undefined).GET('/api/v1/me', { fetch: fetchMock })
    expect(fetchMock.mock.calls[0]?.[0].headers.has('Authorization')).toBe(false)
  })
})

describe('shouldRetry', () => {
  it('retries network and 5xx only, at most twice', () => {
    expect(shouldRetry(0, new TypeError('network'))).toBe(true)
    expect(shouldRetry(0, new ApiError(503))).toBe(true)
    expect(shouldRetry(0, new ApiError(401))).toBe(false)
    expect(shouldRetry(0, new ApiError(403))).toBe(false)
    expect(shouldRetry(2, new ApiError(503))).toBe(false)
  })
})
