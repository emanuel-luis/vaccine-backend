import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { useAuth } from 'react-oidc-context'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useApi } from '../api/client'
import { useSession } from './session'

vi.mock('react-oidc-context')
vi.mock('../api/client', () => ({
  useApi: vi.fn(),
  unwrap: (r: unknown) => r,
  // the mocked GET resolves { data: <payload> }, so unwrapData hands back the payload
  unwrapData: (r: { data: unknown }) => r.data,
}))

const b64 = (o: object) => btoa(JSON.stringify(o)).replace(/=+$/, '')
const token = (payload: object) => `${b64({ alg: 'none' })}.${b64(payload)}.sig`

const claimToken = token({ roles: ['gestor'], region_scope: 'UF:BA' })

function setup(me: unknown | Promise<never>) {
  vi.mocked(useAuth).mockReturnValue({
    isAuthenticated: true,
    user: { access_token: claimToken, profile: { name: 'Token Name' } },
  } as unknown as ReturnType<typeof useAuth>)
  vi.mocked(useApi).mockReturnValue({
    GET: vi.fn(() => (me instanceof Promise ? me : Promise.resolve({ data: me }))),
  } as unknown as ReturnType<typeof useApi>)
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
  return renderHook(() => useSession(), { wrapper })
}

describe('useSession', () => {
  beforeEach(() => vi.resetAllMocks())

  it('falls back to token claims until /me answers', () => {
    const { result } = setup(new Promise<never>(() => {}))
    expect(result.current).toEqual({
      authenticated: true,
      name: 'Token Name',
      roles: ['gestor'],
      scope: { level: 'UF', state: 'BA' },
    })
  })

  it('prefers /me name, roles and scope', async () => {
    const { result } = setup({ name: 'Ana', roles: ['data-admin'], scope: { level: 'BR' } })
    await waitFor(() => expect(result.current.name).toBe('Ana'))
    expect(result.current.roles).toEqual(['data-admin'])
    expect(result.current.scope).toEqual({ level: 'BR' })
  })

  it('keeps the claim scope when /me returns scope null', async () => {
    const { result } = setup({ name: 'Ana', roles: ['gestor'], scope: null })
    await waitFor(() => expect(result.current.name).toBe('Ana'))
    expect(result.current.scope).toEqual({ level: 'UF', state: 'BA' })
  })

  it('filters unknown roles', async () => {
    const { result } = setup({ name: 'Ana', roles: ['gestor', 'root'], scope: null })
    await waitFor(() => expect(result.current.name).toBe('Ana'))
    expect(result.current.roles).toEqual(['gestor'])
  })

  it('converts a MUN code to string', async () => {
    const { result } = setup({
      name: 'Ana',
      roles: [],
      scope: { level: 'MUN', municipalityCode: 2927408 },
    })
    await waitFor(() => expect(result.current.scope).toEqual({ level: 'MUN', municipalityCode: '2927408' }))
  })
})
