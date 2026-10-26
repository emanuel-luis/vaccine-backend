import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { DbtInvocation, DbtNodeResult, Job, Reconciliation, Source } from '../api/types'
import type { Role } from '../auth/claims'
import { useSession } from '../auth/session'
import { LiveProvider } from '../live/LiveProvider'
import { AppRoutes } from '../app/routes'
import { envelope, renderApp } from '../test/mockApi'
import { DadosExecucaoDetalhe } from './DadosExecucaoDetalhe'
import { DadosExecucoes } from './DadosExecucoes'
import { DadosFontes } from './DadosFontes'
import { DadosReconciliacao } from './DadosReconciliacao'
import { DadosTestes } from './DadosTestes'

vi.mock('../auth/session')
vi.mock('react-oidc-context', () => ({
  useAuth: () => ({ user: { access_token: 'tok' }, isLoading: false, isAuthenticated: true, signinRedirect: vi.fn(), signoutRedirect: vi.fn() }),
}))
vi.mock('../charts/EChart', () => ({ default: ({ label }: { label: string }) => <div role="img" aria-label={label} /> }))

const ID = '22222222-2222-2222-2222-222222222222'
const item = <T,>(data: T) => ({ data, meta: {} })
const enc = new TextEncoder()

interface Call {
  method: string
  url: URL
  body: string
}

/** fetch stub: `handle` answers by method + pathname; the SSE stream is fed by `push`. */
function stub(handle: (c: Call) => Response | undefined) {
  const calls: Call[] = []
  let streamCtl: ReadableStreamDefaultController<Uint8Array> | undefined
  const fn = vi.fn(async (input: RequestInfo | URL) => {
    const req = input instanceof Request ? input : new Request(new URL(String(input), 'http://api.test'))
    const call: Call = { method: req.method, url: new URL(req.url), body: await req.text() }
    if (call.url.pathname === '/api/v1/stream') {
      const body = new ReadableStream<Uint8Array>({ start: (c) => void (streamCtl = c) })
      return new Response(body, { headers: { 'Content-Type': 'text/event-stream' } })
    }
    calls.push(call)
    return handle(call) ?? Response.json({ status: 404 }, { status: 404 })
  })
  vi.stubGlobal('fetch', fn)
  return {
    calls,
    push: (event: string, data: unknown) =>
      act(async () => {
        streamCtl?.enqueue(enc.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`))
        await new Promise((r) => setTimeout(r, 20))
      }),
    waitStream: () => waitFor(() => expect(streamCtl).toBeDefined()),
  }
}

const json = (body: unknown, init?: ResponseInit) => Response.json(body, init)

function as(roles: Role[]) {
  vi.mocked(useSession).mockReturnValue({ authenticated: true, name: 'Ana', roles, scope: { level: 'BR' } })
}

const job = (over: Partial<Job> = {}): Job => ({
  jobId: ID,
  kind: 'dbt-build',
  status: 'succeeded',
  requestedBy: 'ana',
  requestedAt: '2026-10-26T10:00:00Z',
  startedAt: '2026-10-26T10:00:05Z',
  finishedAt: '2026-10-26T10:01:10Z',
  rows: 1200,
  rejects: 3,
  ...over,
})

beforeEach(() => as(['data-admin']))
afterEach(() => vi.unstubAllGlobals())

describe('Fontes de dados', () => {
  const source: Source = {
    sourceId: 'ibge-uf',
    path: 'data/sources/ibge-uf.json.gz',
    tableName: 'raw.ibge_uf',
    publisher: 'IBGE',
    sourceUrl: 'https://example.test/uf',
    licenseNote: 'Dados abertos',
    licenseStatus: 'to_be_confirmed',
    retrievedAt: '2026-10-20T00:00:00Z',
    snapshotDate: '2026-10-20',
    rows: 27,
    sha256: 'ab'.repeat(32),
    verified: true,
  }

  it('shows an empty state when no load happened yet', async () => {
    stub((c) => (c.url.pathname === '/api/v1/admin/sources' ? json(item({ sources: [] })) : undefined))
    renderApp(<DadosFontes />)
    expect(await screen.findByText('Nenhuma carga ainda')).toBeInTheDocument()
    expect(screen.queryByRole('table')).toBeNull()
  })

  it('lists sources with the license badge, short sha256 and copy button', async () => {
    const writeText = vi.fn(() => Promise.resolve())
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } })
    stub((c) =>
      c.url.pathname === '/api/v1/admin/sources'
        ? json(item({ runId: ID, loadedAt: '2026-10-26T10:00:00Z', sources: [source, { ...source, sourceId: 'cnes', licenseStatus: 'confirmed', tableName: null }] }))
        : undefined,
    )
    renderApp(<DadosFontes />)
    const table = await screen.findByRole('table')
    expect(within(table).getByText('A confirmar')).toBeInTheDocument()
    expect(within(table).getByText('Confirmada')).toBeInTheDocument()
    expect(within(table).getAllByText('abababababab')).toHaveLength(2)
    await userEvent.click(screen.getByRole('button', { name: 'Copiar sha256 de ibge-uf' }))
    expect(writeText).toHaveBeenCalledWith(source.sha256)
    expect(await screen.findByText('Copiado')).toBeInTheDocument()
  })
})

describe('Execuções', () => {
  it('launches a job and sends the kind', async () => {
    const s = stub((c) => {
      if (c.url.pathname !== '/api/v1/admin/jobs') return undefined
      return c.method === 'POST' ? json(item(job({ status: 'queued' })), { status: 202 }) : json(envelope([]))
    })
    renderApp(<DadosExecucoes />)
    await userEvent.click(await screen.findByRole('button', { name: 'Rodar dbt build' }))
    await waitFor(() => expect(s.calls.find((c) => c.method === 'POST')?.body).toBe('{"kind":"dbt-build"}'))
  })

  it('shows the already-running message on 409', async () => {
    stub((c) => {
      if (c.url.pathname !== '/api/v1/admin/jobs') return undefined
      return c.method === 'POST' ? json({ status: 409, code: 'job_already_active' }, { status: 409 }) : json(envelope([]))
    })
    renderApp(<DadosExecucoes />)
    await userEvent.click(await screen.findByRole('button', { name: 'Reconciliar' }))
    expect(await screen.findByText('Já existe uma execução em andamento')).toBeInTheDocument()
  })

  it('disables the buttons while a job is queued or running and links rows to the detail', async () => {
    stub((c) => (c.url.pathname === '/api/v1/admin/jobs' ? json(envelope([job({ status: 'running', finishedAt: null })])) : undefined))
    renderApp(<DadosExecucoes />)
    const link = await screen.findByRole('link', { name: 'dbt build' })
    expect(link).toHaveAttribute('href', `/dados/execucoes/${ID}`)
    expect(screen.getByText('Em execução')).toBeInTheDocument()
    for (const name of ['Recarregar dados de referência', 'Rodar dbt build', 'Reconciliar'])
      expect(screen.getByRole('button', { name })).toBeDisabled()
  })
})

describe('Execução: registro ao vivo', () => {
  const line = (seq: number) => ({ seq, loggedAt: '2026-10-26T10:00:06Z', level: seq === 2 ? 'warn' : 'info', message: `linha ${seq}` })
  let stored: ReturnType<typeof line>[]
  let s: ReturnType<typeof stub>

  const afterSeqs = () => s.calls.filter((c) => c.url.pathname.endsWith('/log')).map((c) => c.url.searchParams.get('afterSeq'))
  const logLines = () => within(screen.getByRole('log')).queryAllByText(/^linha \d+$/).map((e) => e.textContent)

  async function open() {
    stored = [line(1), line(2)]
    s = stub((c) => {
      if (c.url.pathname === `/api/v1/admin/jobs/${ID}`) return json(item(job({ status: 'running', finishedAt: null })))
      if (c.url.pathname === `/api/v1/admin/jobs/${ID}/log`) {
        const after = Number(c.url.searchParams.get('afterSeq'))
        return json(envelope(stored.filter((l) => l.seq > after)))
      }
      return undefined
    })
    renderApp(
      <LiveProvider scope={{ level: 'BR' }}>
        <Routes>
          <Route path="/dados/execucoes/:jobId" element={<DadosExecucaoDetalhe />} />
        </Routes>
      </LiveProvider>,
      `/dados/execucoes/${ID}`,
    )
    await waitFor(() => expect(logLines()).toEqual(['linha 1', 'linha 2']))
    await s.waitStream()
  }
  const event = (seq: number, extra: object = {}) => ({ jobId: ID, kind: 'dbt-build', status: 'running', seq, level: 'info', message: `linha ${seq}`, ...extra })

  it('is an aria-live log, styles levels, appends in order and ignores seqs already seen', async () => {
    await open()
    const log = screen.getByRole('log')
    expect(log).toHaveAttribute('aria-live', 'polite')
    expect(within(log).getByText('AVISO')).toBeInTheDocument()
    stored.push(line(3))
    await s.push('job', event(3))
    await s.push('job', event(3))
    await s.push('job', event(2))
    expect(logLines()).toEqual(['linha 1', 'linha 2', 'linha 3'])
    expect(afterSeqs()).toEqual(['0']) // only the mount load: no refetch for lines in order or repeated
  })

  it('resyncs from the API when a seq is skipped', async () => {
    await open()
    stored.push(line(3), line(4), line(5))
    await s.push('job', event(5))
    await waitFor(() => expect(logLines()).toEqual(['linha 1', 'linha 2', 'linha 3', 'linha 4', 'linha 5']))
    expect(afterSeqs().at(-1)).toBe('2')
  })

  it('resyncs on ready (reconnect)', async () => {
    await open()
    const before = afterSeqs().length
    stored.push(line(3))
    await s.push('ready', {})
    await waitFor(() => expect(logLines()).toContain('linha 3'))
    expect(afterSeqs().length).toBeGreaterThan(before)
  })

  it('scrolls to the end on new lines until the toggle pauses it', async () => {
    const setTop = vi.fn()
    vi.spyOn(Element.prototype, 'scrollHeight', 'get').mockReturnValue(900)
    vi.spyOn(Element.prototype, 'scrollTop', 'set').mockImplementation(setTop)
    await open()
    expect(setTop).toHaveBeenCalledWith(900)
    setTop.mockClear()
    stored.push(line(3))
    await s.push('job', event(3))
    expect(setTop).toHaveBeenCalledTimes(1)

    const toggle = screen.getByRole('button', { name: 'Pausar rolagem' })
    await userEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-pressed', 'true')
    setTop.mockClear()
    stored.push(line(4))
    await s.push('job', event(4))
    expect(logLines()).toContain('linha 4')
    expect(setTop).not.toHaveBeenCalled()
    vi.restoreAllMocks()
  })

  it('refetches the job on a status-change event', async () => {
    await open()
    const jobCalls = () => s.calls.filter((c) => c.url.pathname === `/api/v1/admin/jobs/${ID}`).length
    const before = jobCalls()
    await s.push('job', { jobId: ID, kind: 'dbt-build', status: 'succeeded' })
    await waitFor(() => expect(jobCalls()).toBe(before + 1))
  })
})

describe('Testes de qualidade', () => {
  const inv: DbtInvocation = {
    invocationId: 'inv-1',
    command: 'build',
    generatedAt: '2026-10-26T10:00:00Z',
    elapsedS: 75,
    dbtVersion: '1.12.0',
    success: false,
    batchGeneration: 7,
  }
  const results: DbtNodeResult[] = [
    { uniqueId: 'test.a', name: 'not_null_a', resourceType: 'test', status: 'fail', failures: 4, executionTimeS: 0.4, message: 'Got 4 results' },
    { uniqueId: 'model.b', name: 'mart_b', resourceType: 'model', status: 'success', executionTimeS: 2 },
  ]

  it('summarizes the latest invocation and filters results by status and type through the API', async () => {
    const s = stub((c) => {
      if (c.url.pathname === '/api/v1/admin/dbt/invocations') return json(envelope([inv]))
      if (c.url.pathname === '/api/v1/admin/dbt/invocations/inv-1/results') {
        const st = c.url.searchParams.get('status')
        const ty = c.url.searchParams.get('resourceType')
        return json(envelope(results.filter((r) => (!st || r.status === st) && (!ty || r.resourceType === ty))))
      }
      return undefined
    })
    renderApp(<DadosTestes />)
    expect(await screen.findByText('not_null_a')).toBeInTheDocument()
    expect(screen.getByText('mart_b')).toBeInTheDocument()
    expect(screen.getByText('Geração absorvida').nextElementSibling).toHaveTextContent('7')
    expect(screen.getByText('1 min 15 s')).toBeInTheDocument()

    await userEvent.selectOptions(screen.getByLabelText('Status'), 'fail')
    await waitFor(() => expect(screen.queryByText('mart_b')).toBeNull())
    expect(screen.getByText('not_null_a')).toBeInTheDocument()
    expect(s.calls.at(-1)?.url.searchParams.get('status')).toBe('fail')

    await userEvent.selectOptions(screen.getByLabelText('Status'), '')
    await userEvent.selectOptions(screen.getByLabelText('Tipo'), 'model')
    await waitFor(() => expect(screen.queryByText('not_null_a')).toBeNull())
    expect(screen.getByText('mart_b')).toBeInTheDocument()
    expect(s.calls.at(-1)?.url.searchParams.get('resourceType')).toBe('model')
  })
})

describe('Reconciliação e ingestão', () => {
  const run: Reconciliation = {
    runId: ID,
    status: 'succeeded',
    startedAt: '2026-10-26T10:00:00Z',
    checks: [
      { checkName: 'doses_total', passed: true, expectedTotal: 100, actualTotal: 100, keysCompared: 10, keysMismatched: 0, openGeneration: 2, diffs: [] },
      {
        checkName: 'doses_by_day',
        passed: false,
        expectedTotal: 100,
        actualTotal: 98,
        keysCompared: 10,
        keysMismatched: 1,
        openGeneration: 2,
        diffs: [{ lineNo: 1, diffKey: '{"day":"2026-10-25","uf":"BA"}', expected: 5, actual: 3 }],
      },
    ],
  }

  it('renders checks, parsed diff keys, a zero-filled ingest series and rejects by reason', async () => {
    const hour = new Date(Math.floor(Date.now() / 3_600_000) * 3_600_000).toISOString()
    stub((c) => {
      if (c.url.pathname === '/api/v1/admin/reconciliation') return json(envelope([run]))
      if (c.url.pathname === '/api/v1/admin/ingest')
        return json(item({ acceptedPerHour: [{ hour, doses: 42 }], rejectsByReason: [{ reason: 'invalid_vaccine', httpStatus: 422, count: 9 }] }))
      return undefined
    })
    renderApp(<DadosReconciliacao />)
    expect(await screen.findByText('Aprovado')).toBeInTheDocument()
    expect(screen.getByText('Reprovado')).toBeInTheDocument()
    expect(screen.getByText('day: 2026-10-25, uf: BA')).toBeInTheDocument()
    expect(screen.getByText('invalid_vaccine')).toBeInTheDocument()

    await userEvent.click(await screen.findByRole('button', { name: 'Ver tabela' }))
    const rows = within(screen.getByRole('table', { name: /últimas 24 horas/ })).getAllByRole('row')
    expect(rows).toHaveLength(25) // header + 24 hours, empty hours filled with 0
    expect(within(rows.at(-1)!).getByText('42')).toBeInTheDocument()
    expect(within(rows[1]!).getByText('0')).toBeInTheDocument()
  })
})

describe('acesso', () => {
  it('redirects a user without data-admin away from /dados', async () => {
    as(['gestor'])
    stub(() => undefined)
    renderApp(<AppRoutes />, '/dados/fontes')
    await waitFor(() => expect(screen.getByTestId('loc')).toHaveTextContent('/acesso-negado'))
  })
})
