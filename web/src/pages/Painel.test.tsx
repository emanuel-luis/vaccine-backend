import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useSession } from '../auth/session'
import { envelope, mockFetch, renderApp, VACCINES } from '../test/mockApi'
import { Painel } from './Painel'

vi.mock('../auth/session')
vi.mock('react-oidc-context', () => ({ useAuth: () => ({ user: { access_token: 'tok' } }) }))
vi.mock('../charts/EChart', () => ({ default: ({ label }: { label: string }) => <div role="img" aria-label={label} /> }))

const year = new Date().getFullYear()

const summary = {
  data: {
    dosesInPeriod: 1234567,
    dosesToday: 8901,
    today: '2026-10-25',
    batchGeneration: 7,
    liveDosesPending: null,
    suppressedFields: ['liveDosesPending'],
  },
  meta: {},
}

const routes = {
  '/api/v1/reference/vaccines': envelope(VACCINES),
  '/api/v1/reference/regions': envelope([]),
  '/api/v1/dashboard/summary': summary,
  '/api/v1/dashboard/doses/timeseries': envelope([
    { periodStart: '2026-01-05', doses: 120, suppressed: false },
    { periodStart: '2026-01-12', doses: null, suppressed: true },
  ]),
  '/api/v1/dashboard/doses/by-vaccine': envelope([
    { vaccineCode: 'BCG', vaccineName: 'BCG', doseLabel: 'DU', doses: 4321, suppressed: false },
    { vaccineCode: 'PENTA', vaccineName: 'Pentavalente', doseLabel: 'D1', doses: null, suppressed: true },
  ]),
  '/api/v1/dashboard/doses/by-age-band': envelope([{ ageBandYears: 1, doses: 77, suppressed: false }]),
}

describe('Painel', () => {
  beforeEach(() =>
    vi.mocked(useSession).mockReturnValue({ authenticated: true, name: 'Ana', roles: ['gestor'], scope: { level: 'BR' } }),
  )
  afterEach(() => vi.unstubAllGlobals())

  it('renders KPIs (suppressed as "< 5") and sends the filters from the URL', async () => {
    const fetchMock = mockFetch(routes)
    renderApp(<Painel />, '/painel?regiao=UF:BA&faixa=1')

    expect(await screen.findByText('1.234.567')).toBeInTheDocument()
    expect(screen.getByText('8.901')).toBeInTheDocument()
    const pending = screen.getByText('Doses ao vivo pendentes').closest('div')
    expect(pending).toHaveTextContent('< 5')

    const urls = fetchMock.mock.calls.map(([r]) => new URL(r instanceof Request ? r.url : String(r), 'http://x'))
    const s = urls.find((u) => u.pathname === '/api/v1/dashboard/summary')
    expect(s?.searchParams.get('from')).toBe(`${year}-01-01`)
    expect(s?.searchParams.get('region')).toBe('UF:BA')
    expect(s?.searchParams.get('ageBand')).toBe('1')
    const byVaccine = urls.find((u) => u.pathname.endsWith('/by-vaccine'))
    expect(byVaccine?.searchParams.get('ageBand')).toBe('1')
    expect(byVaccine?.searchParams.has('vaccine')).toBe(false)
    const byAge = urls.find((u) => u.pathname.endsWith('/by-age-band'))
    expect(byAge?.searchParams.has('ageBand')).toBe(false)
  })

  it('shows suppressed bars and points as "< 5" in the table alternative', async () => {
    mockFetch(routes)
    renderApp(<Painel />)
    const panel = (await screen.findByRole('region', { name: 'Doses por vacina' })).closest('section') as HTMLElement
    await userEvent.click(await within(panel).findByRole('button', { name: 'Ver tabela' }))
    expect(within(panel).getByRole('cell', { name: '< 5' })).toBeInTheDocument()
    expect(within(panel).getByRole('cell', { name: '4.321' })).toBeInTheDocument()

    const ts = (await screen.findByRole('region', { name: 'Doses ao longo do tempo' })) as HTMLElement
    await userEvent.click(within(ts).getByRole('button', { name: 'Ver tabela' }))
    expect(within(ts).getByRole('cell', { name: '< 5' })).toBeInTheDocument()
  })

  it('shows an error panel with retry on 503', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({ status: 503 }, { status: 503 })))
    renderApp(<Painel />)
    expect((await screen.findAllByRole('alert'))[0]).toHaveTextContent('Tente novamente')
    expect(screen.getAllByRole('button', { name: 'Tentar novamente' }).length).toBeGreaterThan(0)
  })
})
