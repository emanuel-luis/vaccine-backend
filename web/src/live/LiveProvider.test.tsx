import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ConfigContext } from '../config'
import { backoffMs, LiveProvider, useLive } from './LiveProvider'

vi.mock('react-oidc-context', () => ({ useAuth: () => ({ user: { access_token: 'tok' } }) }))

const enc = new TextEncoder()

/** A fetch response whose body the test feeds chunk by chunk. */
function fakeStream(signal?: AbortSignal | null) {
  let ctl!: ReadableStreamDefaultController<Uint8Array>
  const body = new ReadableStream<Uint8Array>({ start: (c) => void (ctl = c) })
  signal?.addEventListener('abort', () => {
    try {
      ctl.error(new DOMException('aborted', 'AbortError'))
    } catch {
      // already closed
    }
  })
  return {
    response: new Response(body, { headers: { 'Content-Type': 'text/event-stream' } }),
    push: (s: string) => ctl.enqueue(enc.encode(s)),
    close: () => ctl.close(),
  }
}

const sse = (event: string, data: unknown) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`

function Probe() {
  const { status, recentDoses } = useLive()
  return (
    <>
      <output data-testid="status">{status}</output>
      <output data-testid="doses">{recentDoses}</output>
    </>
  )
}

function setVisibility(state: 'visible' | 'hidden') {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state })
  document.dispatchEvent(new Event('visibilitychange'))
}

const flush = () => act(() => vi.advanceTimersByTimeAsync(0))
const advance = (ms: number) => act(() => vi.advanceTimersByTimeAsync(ms))

describe('LiveProvider', () => {
  let streams: ReturnType<typeof fakeStream>[]
  let fetchMock: ReturnType<typeof vi.fn>
  let invalidate: ReturnType<typeof vi.fn>

  beforeEach(() => {
    vi.useFakeTimers()
    streams = []
    fetchMock = vi.fn((_url: URL, init: RequestInit) => {
      const s = fakeStream(init.signal)
      streams.push(s)
      return Promise.resolve(s.response)
    })
    vi.stubGlobal('fetch', fetchMock)
    setVisibility('visible')
    const client = new QueryClient()
    invalidate = vi.fn(() => Promise.resolve())
    client.invalidateQueries = invalidate as unknown as typeof client.invalidateQueries
    const config = { apiBaseUrl: 'http://api.test', oidc: { authority: 'http://kc', clientId: 'vaccine-web' } }
    render(
      <ConfigContext.Provider value={config}>
        <QueryClientProvider client={client}>
          <LiveProvider scope={{ level: 'UF', state: 'BA' }}>
            <Probe />
          </LiveProvider>
        </QueryClientProvider>
      </ConfigContext.Provider>,
    )
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  const status = () => screen.getByTestId('status').textContent
  const keys = () => invalidate.mock.calls.map(([f]) => JSON.stringify((f as { queryKey: unknown }).queryKey))

  it('sends the bearer token and the scope region, and parses events split across chunks', async () => {
    await flush()
    expect(status()).toBe('reconnecting')
    const [url, init] = fetchMock.mock.calls[0] as [URL, RequestInit]
    expect(url.pathname).toBe('/api/v1/stream')
    expect(url.searchParams.get('region')).toBe('UF:BA')
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer tok')

    const ready = sse('ready', { serverTime: 't', batchGeneration: 3, region: 'UF:BA' })
    streams[0]!.push(ready.slice(0, 20))
    await flush()
    expect(status()).toBe('reconnecting')
    streams[0]!.push(`${ready.slice(20)}: ping\n\n`)
    await flush()
    expect(status()).toBe('live')
    expect(keys()).toEqual(['["live"]'])
  })

  it('throttles invalidation of live queries to once per 5 s and updates the ticker at most every 30 s', async () => {
    await flush()
    streams[0]!.push(sse('ready', {}))
    await flush()
    invalidate.mockClear()

    const doses = (total: number) => sse('doses', { tick: 1, from: 'a', to: 'b', total, byVaccine: {}, municipalities: [] })
    streams[0]!.push(doses(4))
    await flush()
    expect(invalidate).toHaveBeenCalledTimes(1) // leading call
    expect(screen.getByTestId('doses').textContent).toBe('4') // first ticker update is immediate

    streams[0]!.push(doses(3) + doses(2))
    await advance(2_000)
    expect(invalidate).toHaveBeenCalledTimes(1)
    expect(screen.getByTestId('doses').textContent).toBe('4')

    await advance(3_000)
    expect(invalidate).toHaveBeenCalledTimes(2) // one trailing call for the two events
    expect(screen.getByTestId('doses').textContent).toBe('4')

    await advance(25_000)
    expect(screen.getByTestId('doses').textContent).toBe('9')
  })

  it('batch invalidates live and alert queries', async () => {
    await flush()
    streams[0]!.push(sse('batch', { generation: 8, invocationId: 'x' }))
    await flush()
    expect(keys()).toEqual(['["live"]', '["alerts"]'])
  })

  it('reconnects with exponential backoff from 1 s and resets it after ready', async () => {
    await flush()
    streams[0]!.close()
    await flush()
    expect(status()).toBe('reconnecting')
    await advance(999)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    await advance(1)
    expect(fetchMock).toHaveBeenCalledTimes(2)

    streams[1]!.close()
    await advance(1_999)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    await advance(1)
    expect(fetchMock).toHaveBeenCalledTimes(3)

    streams[2]!.push(sse('ready', {}))
    await flush()
    expect(status()).toBe('live')
    streams[2]!.close()
    await advance(1_000)
    expect(fetchMock).toHaveBeenCalledTimes(4)
  })

  it('caps the backoff at 30 s', () => {
    expect([0, 1, 2, 5, 6, 20].map(backoffMs)).toEqual([1_000, 2_000, 4_000, 30_000, 30_000, 30_000])
  })

  it('pauses while the tab is hidden and reconnects when it is visible again', async () => {
    await flush()
    streams[0]!.push(sse('ready', {}))
    await flush()
    expect(status()).toBe('live')

    setVisibility('hidden')
    await flush()
    expect(status()).toBe('paused')
    await advance(60_000)
    expect(fetchMock).toHaveBeenCalledTimes(1)

    setVisibility('visible')
    await flush()
    expect(fetchMock).toHaveBeenCalledTimes(2)
    streams[1]!.push(sse('ready', {}))
    await flush()
    expect(status()).toBe('live')
  })
})
