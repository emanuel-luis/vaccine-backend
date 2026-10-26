import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { LowCoverage } from '../api/types'
import { useSession } from '../auth/session'
import { envelope, mockFetch, renderApp, VACCINES } from '../test/mockApi'
import { Alertas, sortByGap } from './Alertas'

vi.mock('../auth/session')
vi.mock('react-oidc-context', () => ({ useAuth: () => ({ user: { access_token: 'tok' } }) }))

const alert = (over: Partial<LowCoverage>): LowCoverage => ({
  level: 'state',
  code: '29',
  abbr: 'BA',
  name: 'Bahia',
  vaccineCode: 'BCG',
  doseLabel: 'DU',
  coveragePct: 87.3,
  goalPct: 90,
  gapPp: 2.7,
  numerator: 873,
  denominator: 1000,
  ...over,
})

describe('Alertas', () => {
  beforeEach(() =>
    vi.mocked(useSession).mockReturnValue({ authenticated: true, name: 'Ana', roles: ['gestor'], scope: { level: 'BR' } }),
  )
  afterEach(() => vi.unstubAllGlobals())

  it('sorts by gap, shows coverage against the goal and links to the area', async () => {
    const fetchMock = mockFetch({
      '/api/v1/reference/vaccines': envelope(VACCINES),
      '/api/v1/alerts/low-coverage': envelope([
        alert({ code: '29', name: 'Bahia', gapPp: 2.7 }),
        alert({ code: '35', abbr: 'SP', name: 'São Paulo', coveragePct: 70, gapPp: 20, numerator: 700 }),
      ]),
    })
    renderApp(<Alertas />, '/alertas?ano=2025')

    const rows = await screen.findAllByRole('row')
    expect(within(rows[1]!).getByRole('rowheader')).toHaveTextContent('São Paulo (SP)')
    expect(within(rows[2]!).getByRole('rowheader')).toHaveTextContent('Bahia (BA)')
    expect(within(rows[2]!).getByText('87,3%')).toBeInTheDocument()
    expect(within(rows[2]!).getByText('90,0%')).toBeInTheDocument()
    expect(within(rows[2]!).getByRole('link', { name: /Bahia/ })).toHaveAttribute(
      'href',
      '/cobertura/BA?ano=2025&vacina=BCG&dose=DU',
    )

    const url = fetchMock.mock.calls.map(([r]) => new URL(r instanceof Request ? r.url : String(r), 'http://x')).find((u) => u.pathname.endsWith('/low-coverage'))
    expect(url?.searchParams.get('level')).toBe('state')
    expect(url?.searchParams.get('year')).toBe('2025')
  })

  it('sortByGap puts the largest gap first', () => {
    const out = sortByGap([alert({ name: 'A', gapPp: 1 }), alert({ name: 'B', gapPp: 9 })])
    expect(out.map((r) => r.name)).toEqual(['B', 'A'])
  })

  it('municipality level asks for a UF first, then queries with it and links inside the UF', async () => {
    const fetchMock = mockFetch({
      '/api/v1/reference/vaccines': envelope(VACCINES),
      '/api/v1/alerts/low-coverage': envelope([alert({ level: 'municipality', code: '2927408', abbr: null, name: 'Salvador' })]),
    })
    renderApp(<Alertas />, '/alertas?nivel=municipality')
    expect(await screen.findByText('Escolha uma UF')).toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([r]) => String(r instanceof Request ? r.url : r).includes('low-coverage'))).toBe(false)

    await userEvent.selectOptions(screen.getByLabelText('UF'), 'BA')
    expect(await screen.findByRole('link', { name: /Salvador/ })).toHaveAttribute(
      'href',
      expect.stringContaining('/cobertura/BA/2927408'),
    )
  })

  it('shows the empty state', async () => {
    mockFetch({ '/api/v1/reference/vaccines': envelope(VACCINES), '/api/v1/alerts/low-coverage': envelope([]) })
    renderApp(<Alertas />)
    expect(await screen.findByText('Nenhum alerta')).toBeInTheDocument()
  })

  it('shows an error with retry', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({ status: 503 }, { status: 503 })))
    renderApp(<Alertas />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Tente novamente')
  })
})
