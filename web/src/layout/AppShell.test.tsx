import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Role, Scope } from '../auth/claims'
import { useSession } from '../auth/session'
import { AppShell } from './AppShell'

vi.mock('../auth/session')
vi.mock('react-oidc-context', () => ({
  useAuth: () => ({ signinRedirect: vi.fn(), signoutRedirect: vi.fn() }),
}))

function show(authenticated: boolean, roles: Role[], scope: Scope | null = { level: 'BR' }) {
  vi.mocked(useSession).mockReturnValue({ authenticated, name: 'Ana Souza', roles, scope })
  render(
    <MemoryRouter>
      <AppShell />
    </MemoryRouter>,
  )
  return within(screen.getByRole('navigation', { name: 'Principal' }))
}

describe('AppShell', () => {
  beforeEach(() => vi.resetAllMocks())

  it('shows the common nav and no Dados group for a gestor', () => {
    const nav = show(true, ['gestor'])
    for (const name of ['Painel', 'Cobertura', 'Alertas', 'Campanhas', 'Sobre'])
      expect(nav.getByRole('link', { name })).toBeInTheDocument()
    expect(nav.queryByText('Dados')).toBeNull()
    expect(nav.queryByRole('link', { name: 'Fontes' })).toBeNull()
  })

  it('shows the Dados group for a data-admin', () => {
    const nav = show(true, ['data-admin'])
    expect(nav.getByText('Dados')).toBeInTheDocument()
    expect(nav.getByRole('link', { name: 'Reconciliação' })).toBeInTheDocument()
  })

  it('shows user, scope badge, live status and skip link', () => {
    show(true, ['gestor'], { level: 'UF', state: 'BA' })
    expect(screen.getByText('Ana Souza')).toBeInTheDocument()
    expect(screen.getByText('UF: BA')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Pausado')
    expect(screen.getByRole('link', { name: 'Ir para o conteúdo' })).toHaveAttribute('href', '#conteudo')
    expect(screen.getByRole('button', { name: 'Sair' })).toBeInTheDocument()
  })

  it('signed out: only Sobre and a login button', () => {
    const nav = show(false, [])
    expect(nav.getAllByRole('link')).toHaveLength(1)
    expect(nav.getByRole('link', { name: 'Sobre' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Entrar' })).toBeInTheDocument()
  })
})
