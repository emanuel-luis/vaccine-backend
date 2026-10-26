import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Role, Scope } from '../auth/claims'
import { useSession } from '../auth/session'
import { envelope, mockFetch, renderApp, row, VACCINES } from '../test/mockApi'
import { Cobertura } from './Cobertura'

vi.mock('../auth/session')
vi.mock('react-oidc-context', () => ({ useAuth: () => ({ user: { access_token: 'tok' } }) }))
vi.mock('../map/MapCanvas', () => ({ default: () => <div data-testid="canvas" /> }))

const states = [
  row({ code: '29', abbr: 'BA', name: 'Bahia', value: 91.2 }),
  row({ code: '35', abbr: 'SP', name: 'São Paulo', value: null, suppressed: true, reason: 'small_cell' }),
]
const muns = [
  row({ code: '2927408', abbr: null, name: 'Salvador', value: 80 }),
  row({ code: '2900108', abbr: null, name: 'Abaíra', value: null, suppressed: true, reason: 'small_cell' }),
]

function routes() {
  return {
    '/api/v1/reference/vaccines': envelope(VACCINES),
    '/api/v1/reference/regions': envelope([]),
    '/api/v1/dashboard/map': (u: URL) =>
      u.searchParams.get('level') === 'state' ? envelope(states, 1) : envelope(muns, 1),
    '/api/v1/dashboard/health-units': envelope([
      { cnesCode: '0000001', unitName: 'UBS Centro', latitude: null, longitude: null, doses: 120, suppressed: false },
      { cnesCode: '0000002', unitName: 'UBS Norte', latitude: null, longitude: null, doses: null, suppressed: true },
    ]),
    '/geo/ibge_malha_uf.geojson': { type: 'FeatureCollection', features: [] },
    '/geo/ibge_malha_municipios_ba.geojson': { type: 'FeatureCollection', features: [] },
  }
}

function show(entry: string, roles: Role[] = ['profissional'], scope: Scope = { level: 'BR' }) {
  vi.mocked(useSession).mockReturnValue({ authenticated: true, name: 'Ana', roles, scope })
  renderApp(
    <Routes>
      <Route path="/cobertura" element={<Cobertura />} />
      <Route path="/cobertura/:uf" element={<Cobertura />} />
      <Route path="/cobertura/:uf/:municipio" element={<Cobertura />} />
    </Routes>,
    entry,
  )
}

describe('Cobertura', () => {
  beforeEach(() => vi.resetAllMocks())
  afterEach(() => vi.unstubAllGlobals())

  it('state level: asks for the default indicator dose, shows suppressed states and drills down', async () => {
    const fetchMock = mockFetch(routes())
    show('/cobertura')
    const list = within(await screen.findByRole('navigation', { name: 'Áreas do mapa' }))
    expect(list.getByRole('button', { name: /Bahia/ })).toHaveTextContent('91,2%')
    expect(list.getByRole('button', { name: /São Paulo/ })).toHaveTextContent('< 5')
    expect(screen.getByText(/1 área tem dados insuficientes/)).toBeInTheDocument()

    const q = fetchMock.mock.calls
      .map(([r]) => new URL(r instanceof Request ? r.url : String(r), 'http://x'))
      .find((u) => u.pathname === '/api/v1/dashboard/map')
    expect(q?.searchParams.get('metric')).toBe('coverage')
    expect(q?.searchParams.get('vaccine')).toBe('BCG')
    expect(q?.searchParams.get('dose')).toBe('DU')
    expect(q?.searchParams.get('year')).toMatch(/^\d{4}$/)

    await userEvent.click(list.getByRole('button', { name: /Bahia/ }))
    expect(screen.getByTestId('loc')).toHaveTextContent('/cobertura/BA')
  })

  it('municipality level: breadcrumb, municipality list and unit table with "< 5"', async () => {
    mockFetch(routes())
    show('/cobertura/BA/2927408')
    const table = await screen.findByRole('table', { name: 'Unidades de saúde do município' })
    expect(within(table).getByRole('cell', { name: '120' })).toBeInTheDocument()
    expect(within(table).getByRole('cell', { name: '< 5' })).toBeInTheDocument()
    const crumbs = within(screen.getByRole('navigation', { name: 'Navegação por região' }))
    expect(crumbs.getByRole('link', { name: 'Brasil' })).toBeInTheDocument()
    expect(crumbs.getByRole('link', { name: 'BA' })).toBeInTheDocument()
    expect(crumbs.getByText('Salvador')).toBeInTheDocument()
  })

  it('switching to doses drops the coverage requirement and sends a period', async () => {
    const fetchMock = mockFetch(routes())
    show('/cobertura')
    await screen.findByRole('navigation', { name: 'Áreas do mapa' })
    await userEvent.click(screen.getByRole('button', { name: 'Doses' }))
    await screen.findByRole('navigation', { name: 'Áreas do mapa' })
    const last = fetchMock.mock.calls
      .map(([r]) => new URL(r instanceof Request ? r.url : String(r), 'http://x'))
      .filter((u) => u.pathname === '/api/v1/dashboard/map')
      .at(-1)
    expect(last?.searchParams.get('metric')).toBe('doses')
    expect(last?.searchParams.has('from')).toBe(true)
    expect(last?.searchParams.has('vaccine')).toBe(false)
  })

  it('export is visible to gestor only, and coverage export needs a state', async () => {
    mockFetch(routes())
    show('/cobertura', ['gestor'])
    await screen.findByRole('navigation', { name: 'Áreas do mapa' })
    expect(screen.queryByRole('button', { name: /^Exportar/ })).toBeNull()
  })

  it('gestor can export a state coverage', async () => {
    mockFetch(routes())
    show('/cobertura/BA', ['gestor'])
    await screen.findByRole('navigation', { name: 'Áreas do mapa' })
    expect(screen.getByRole('button', { name: 'Exportar municípios (CSV)' })).toBeInTheDocument()
  })

  it('coverage mode ignores faixa from the URL: no ageBand on the map request, map renders', async () => {
    const fetchMock = mockFetch(routes())
    show('/cobertura?faixa=1')
    await screen.findByRole('navigation', { name: 'Áreas do mapa' })
    const q = fetchMock.mock.calls
      .map(([r]) => new URL(r instanceof Request ? r.url : String(r), 'http://x'))
      .filter((u) => u.pathname === '/api/v1/dashboard/map')
    expect(q.length).toBeGreaterThan(0)
    expect(q.every((u) => !u.searchParams.has('ageBand'))).toBe(true)
    expect(screen.queryByLabelText('Faixa etária')).toBeNull()
  })

  it('health-units export carries from and to in coverage mode', async () => {
    Object.assign(URL, { createObjectURL: () => 'blob:x', revokeObjectURL: () => {} })
    const fetchMock = mockFetch({ ...routes(), '/api/v1/exports/health-units': {} })
    show('/cobertura/BA/2927408', ['gestor'])
    await screen.findByRole('table', { name: 'Unidades de saúde do município' })
    await userEvent.click(screen.getByRole('button', { name: 'Exportar unidades (CSV)' }))
    await vi.waitFor(() => {
      const u = fetchMock.mock.calls
        .map(([r]) => new URL(r instanceof Request ? r.url : String(r), 'http://x'))
        .find((x) => x.pathname === '/api/v1/exports/health-units')
      expect(u?.searchParams.get('from')).toMatch(/^\d{4}-\d{2}-\d{2}$/)
      expect(u?.searchParams.get('to')).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    })
  })

  it('export is hidden for a profissional', async () => {
    mockFetch(routes())
    show('/cobertura/BA', ['profissional'])
    await screen.findByRole('navigation', { name: 'Áreas do mapa' })
    expect(screen.queryByRole('button', { name: /^Exportar/ })).toBeNull()
  })

  it('sends a municipality-scoped user to their municipality', async () => {
    mockFetch(routes())
    show('/cobertura', ['profissional'], { level: 'MUN', municipalityCode: '2927408' })
    await screen.findByRole('table', { name: 'Unidades de saúde do município' })
    expect(screen.getByTestId('loc')).toHaveTextContent('/cobertura/BA/2927408')
  })

  it('shows an error panel when the API answers 403', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({ status: 403 }, { status: 403 })))
    show('/cobertura/BA')
    expect(await screen.findByRole('alert')).toHaveTextContent('Você não tem acesso a esta região.')
  })
})
