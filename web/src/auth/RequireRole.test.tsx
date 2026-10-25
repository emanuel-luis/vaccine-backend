import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Role } from './claims'
import { RequireRole } from './RequireRole'
import { useSession } from './session'

vi.mock('./session')

function setRoles(roles: Role[]) {
  vi.mocked(useSession).mockReturnValue({ authenticated: true, name: 'Ana', roles, scope: null })
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route element={<RequireRole roles={['gestor']} />}>
          <Route path="/campanhas/nova" element={<p>formulário</p>} />
        </Route>
        <Route path="/acesso-negado" element={<p>negado</p>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('RequireRole', () => {
  beforeEach(() => vi.resetAllMocks())

  it('renders the route when the role is allowed', () => {
    setRoles(['gestor'])
    renderAt('/campanhas/nova')
    expect(screen.getByText('formulário')).toBeInTheDocument()
  })

  it('redirects to /acesso-negado when the role is missing', () => {
    setRoles(['profissional'])
    renderAt('/campanhas/nova')
    expect(screen.getByText('negado')).toBeInTheDocument()
  })
})
