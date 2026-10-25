import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ConfigContext } from '../config'
import { Painel } from './Painel'

vi.mock('react-oidc-context', () => ({ useAuth: () => ({ user: { access_token: 'tok' } }) }))

const config = { apiBaseUrl: 'http://api.test', oidc: { authority: 'http://kc', clientId: 'vaccine-web' } }

function renderPainel() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <ConfigContext.Provider value={config}>
      <QueryClientProvider client={client}>
        <Painel />
      </QueryClientProvider>
    </ConfigContext.Provider>,
  )
}

afterEach(() => vi.unstubAllGlobals())

describe('Painel', () => {
  it('renders KPIs formatted for pt-BR and sends the year-to-date range', async () => {
    const fetchMock = vi.fn<(req: Request) => Promise<Response>>(async () =>
      Response.json({
        data: {
          dosesInPeriod: 1234567,
          dosesToday: 8901,
          today: '2026-10-25',
          batchGeneration: 7,
          liveDosesPending: 42,
        },
        meta: { layer: 'merged', synthetic: true },
      }),
    )
    vi.stubGlobal('fetch', fetchMock)
    renderPainel()

    expect(await screen.findByText('1.234.567')).toBeInTheDocument()
    expect(screen.getByText('8.901')).toBeInTheDocument()
    expect(screen.getByText('42')).toBeInTheDocument()

    const req = fetchMock.mock.calls[0]?.[0]
    const url = new URL(req?.url ?? '')
    expect(url.pathname).toBe('/api/v1/dashboard/summary')
    expect(url.searchParams.get('from')).toBe(`${new Date().getFullYear()}-01-01`)
    expect(url.searchParams.get('to')).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    expect(req?.headers.get('Authorization')).toBe('Bearer tok')
  })

  it('shows an error panel with retry on 503', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({ status: 503 }, { status: 503 })))
    renderPainel()
    expect(await screen.findByRole('alert')).toHaveTextContent('Tente novamente')
    expect(screen.getByRole('button', { name: 'Tentar novamente' })).toBeInTheDocument()
  })
})
