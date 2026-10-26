import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useSession } from '../auth/session'
import { envelope, mockFetch, renderApp, VACCINES } from '../test/mockApi'
import { FilterBar } from './FilterBar'

vi.mock('../auth/session')
vi.mock('react-oidc-context', () => ({ useAuth: () => ({ user: { access_token: 'tok' } }) }))

const regions = [
  { code: '29', abbr: 'BA', name: 'Bahia', level: 'state' },
  { code: '35', abbr: 'SP', name: 'São Paulo', level: 'state' },
]

function setup(entry: string, scope = { level: 'BR' } as const, indicatorOnly = false) {
  vi.mocked(useSession).mockReturnValue({ authenticated: true, name: 'Ana', roles: ['gestor'], scope })
  mockFetch({ '/api/v1/reference/vaccines': envelope(VACCINES), '/api/v1/reference/regions': envelope(regions) })
  renderApp(<FilterBar indicatorOnly={indicatorOnly} />, entry)
}

const loc = () => screen.getByTestId('loc').textContent

describe('FilterBar', () => {
  beforeEach(() => vi.resetAllMocks())

  it('reads values from the URL', async () => {
    setup('/?de=2026-01-01&ate=2026-02-01&vacina=PENTA&dose=D1&faixa=2')
    expect(screen.getByLabelText('De')).toHaveValue('2026-01-01')
    expect(screen.getByLabelText('Até')).toHaveValue('2026-02-01')
    expect(await screen.findByRole('option', { name: 'Pentavalente' })).toBeInTheDocument()
    expect(screen.getByLabelText('Vacina')).toHaveValue('PENTA')
    expect(screen.getByLabelText('Dose')).toHaveValue('D1')
    expect(screen.getByLabelText('Faixa etária')).toHaveValue('2')
  })

  it('writes changes to the URL, keeping unrelated params, and clears dose when the vaccine changes', async () => {
    setup('/?x=1&vacina=PENTA&dose=D1')
    const user = userEvent.setup()
    await screen.findByRole('option', { name: 'BCG' })
    await user.selectOptions(screen.getByLabelText('Faixa etária'), '3')
    expect(loc()).toContain('faixa=3')
    expect(loc()).toContain('x=1')
    await user.selectOptions(screen.getByLabelText('Vacina'), 'BCG')
    expect(loc()).toContain('vacina=BCG')
    expect(loc()).not.toContain('dose=')
    await user.selectOptions(screen.getByLabelText('Faixa etária'), '')
    expect(loc()).not.toContain('faixa=')
  })

  it('offers regions from the caller scope', async () => {
    setup('/')
    const user = userEvent.setup()
    await screen.findByRole('option', { name: 'Bahia' })
    await user.selectOptions(screen.getByLabelText('Região'), 'UF:BA')
    expect(loc()).toContain('regiao=UF%3ABA')
  })

  it('locks the region for a municipality scope', () => {
    setup('/', { level: 'MUN', municipalityCode: '2927408' } as never)
    expect(screen.getByLabelText('Região')).toBeDisabled()
    expect(screen.getAllByRole('option', { name: 'Município 2927408' })).toHaveLength(1)
  })

  it('in indicator mode lists only indicator doses and defaults to the first', async () => {
    setup('/', { level: 'BR' }, true)
    await screen.findByRole('option', { name: 'Pentavalente' })
    expect(screen.getByLabelText('Vacina')).toHaveValue('BCG')
    expect(screen.queryByRole('option', { name: 'R1' })).toBeNull()
    expect(within(screen.getByLabelText('Vacina')).queryByRole('option', { name: 'Todas' })).toBeNull()
  })

  it('the filters toggle controls the drawer', async () => {
    setup('/')
    const user = userEvent.setup()
    const toggle = screen.getByRole('button', { name: 'Filtros' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    await user.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByRole('form', { name: 'Filtros' })).toHaveClass('open')
  })
})
