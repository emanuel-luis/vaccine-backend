import { render } from '@testing-library/react'
import { useAuth } from 'react-oidc-context'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { RequireAuth } from './RequireAuth'

vi.mock('react-oidc-context')

describe('RequireAuth', () => {
  const signinRedirect = vi.fn()
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(useAuth).mockReturnValue({
      isLoading: false,
      isAuthenticated: false,
      signinRedirect,
    } as unknown as ReturnType<typeof useAuth>)
  })

  it('passes the current path and query as signin state', () => {
    render(
      <MemoryRouter initialEntries={['/cobertura/BA?ano=2025']}>
        <RequireAuth />
      </MemoryRouter>,
    )
    expect(signinRedirect).toHaveBeenCalledWith({ state: '/cobertura/BA?ano=2025' })
  })
})
