import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import type { ReactElement } from 'react'
import { MemoryRouter, useLocation } from 'react-router'
import { vi } from 'vitest'
import type { MapRow, Vaccine } from '../api/types'
import { ConfigContext } from '../config'

type Handler = unknown | ((url: URL) => unknown)

/** Stubs global fetch: answers by pathname with a JSON body, 404 otherwise. Returns the mock. */
export function mockFetch(routes: Record<string, Handler>) {
  const fn = vi.fn(async (input: RequestInfo | URL) => {
    const raw = input instanceof Request ? input.url : String(input)
    const url = new URL(raw, 'http://api.test')
    const h = routes[url.pathname]
    if (h === undefined) return Response.json({ status: 404 }, { status: 404 })
    return Response.json(typeof h === 'function' ? (h as (u: URL) => unknown)(url) : h)
  })
  vi.stubGlobal('fetch', fn)
  return fn
}

export const meta = { source: 'test', synthetic: true, count: 0, layer: 'merged', batchGeneration: 1, suppressedCells: 0 }
export const envelope = <T,>(data: T[], suppressedCells = 0) => ({ data, meta: { ...meta, count: data.length, suppressedCells } })

export const VACCINES: Vaccine[] = [
  { vaccineCode: 'BCG', vaccineName: 'BCG', doseLabel: 'DU', doseNumber: 1, isCoverageIndicator: true, targetAgeYears: 1 },
  { vaccineCode: 'PENTA', vaccineName: 'Pentavalente', doseLabel: 'D1', doseNumber: 1, isCoverageIndicator: true, targetAgeYears: 1 },
  { vaccineCode: 'PENTA', vaccineName: 'Pentavalente', doseLabel: 'R1', doseNumber: 4, isCoverageIndicator: false, targetAgeYears: 2 },
]

export const row = (over: Partial<MapRow>): MapRow => ({
  code: '29',
  abbr: 'BA',
  name: 'Bahia',
  value: 87.3,
  numerator: 873,
  denominator: 1000,
  suppressed: false,
  reason: null,
  ...over,
})

function Probe() {
  const l = useLocation()
  return <output data-testid="loc">{l.pathname + l.search}</output>
}

/** Renders inside QueryClient, ConfigContext and a MemoryRouter that exposes the location. */
export function renderApp(ui: ReactElement, entry = '/') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const config = { apiBaseUrl: 'http://api.test', oidc: { authority: 'http://kc', clientId: 'vaccine-web' } }
  return render(
    <ConfigContext.Provider value={config}>
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[entry]}>
          {ui}
          <Probe />
        </MemoryRouter>
      </QueryClientProvider>
    </ConfigContext.Provider>,
  )
}
