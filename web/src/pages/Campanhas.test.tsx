import { focusManager } from '@tanstack/react-query'
import { act, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Campaign } from '../api/types'
import type { Role } from '../auth/claims'
import { useSession } from '../auth/session'
import { envelope, mockFetch, renderApp, VACCINES } from '../test/mockApi'
import { CampanhaDetalhe } from './CampanhaDetalhe'
import { CampanhaEditar, CampanhaNova } from './CampanhaForm'
import { Campanhas } from './Campanhas'

vi.mock('../auth/session')
vi.mock('react-oidc-context', () => ({ useAuth: () => ({ user: { access_token: 'tok' } }) }))
vi.mock('../charts/EChart', () => ({ default: ({ label }: { label: string }) => <div role="img" aria-label={label} /> }))

const ID = '11111111-1111-1111-1111-111111111111'
const campaign: Campaign = {
  campaignId: ID,
  name: 'Multivacinação',
  vaccineCode: 'BCG',
  doseLabel: null,
  regionScope: 'UF:BA',
  startsOn: '2026-10-01',
  endsOn: '2099-12-31',
  targetDoses: 500,
  createdBy: 'ana',
  createdAt: '2026-09-30T10:00:00Z',
  updatedAt: '2026-09-30T10:00:00Z',
  version: 3,
}
const detail = { data: { ...campaign, progress: { dosesToDate: 120, pct: 24, suppressed: false, daily: [{ date: '2026-10-02', doses: 120, suppressed: false }] } }, meta: {} }

const routes = {
  '/api/v1/reference/vaccines': envelope(VACCINES),
  '/api/v1/reference/regions': envelope([]),
  '/api/v1/campaigns': envelope([campaign]),
  [`/api/v1/campaigns/${ID}`]: detail,
}

function as(roles: Role[]) {
  vi.mocked(useSession).mockReturnValue({ authenticated: true, name: 'Ana', roles, scope: { level: 'BR' } })
}

function app(entry: string) {
  return renderApp(
    <Routes>
      <Route path="/campanhas" element={<Campanhas />} />
      <Route path="/campanhas/nova" element={<CampanhaNova />} />
      <Route path="/campanhas/:id" element={<CampanhaDetalhe />} />
      <Route path="/campanhas/:id/editar" element={<CampanhaEditar />} />
    </Routes>,
    entry,
  )
}

describe('role gating', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('gestor sees new, edit and delete', async () => {
    as(['gestor'])
    mockFetch(routes)
    app('/campanhas')
    expect(await screen.findByRole('link', { name: 'Nova campanha' })).toBeInTheDocument()
    await userEvent.click(await screen.findByRole('link', { name: 'Multivacinação' }))
    expect(await screen.findByRole('link', { name: 'Editar' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Excluir' })).toBeInTheDocument()
  })

  it.each<Role>(['profissional', 'data-admin'])('%s is read-only', async (role) => {
    as([role])
    mockFetch(routes)
    app('/campanhas')
    await screen.findByRole('link', { name: 'Multivacinação' })
    expect(screen.queryByRole('link', { name: 'Nova campanha' })).toBeNull()
    await userEvent.click(screen.getByRole('link', { name: 'Multivacinação' }))
    expect(await screen.findByText('Doses até hoje')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Editar' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Excluir' })).toBeNull()
  })

  it('shows the zero-filled daily table and the progress', async () => {
    as(['profissional'])
    mockFetch(routes)
    app(`/campanhas/${ID}`)
    expect(await screen.findByText('120')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Ver tabela' }))
    const rows = screen.getAllByRole('row').slice(1)
    expect(rows.length).toBeGreaterThan(2)
    expect(within(rows[0]!).getByRole('cell')).toHaveTextContent('0') // 01/10: omitted by the API, zero here
    expect(within(rows[1]!).getByRole('cell')).toHaveTextContent('120')
  })

  it('delete asks for confirmation and sends If-Match with the version', async () => {
    as(['gestor'])
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const req = input instanceof Request ? input : new Request(new URL(String(input), 'http://api.test'), init)
      const path = new URL(req.url).pathname
      if (req.method === 'DELETE') return new Response(null, { status: 204 })
      const body = (routes as Record<string, unknown>)[path]
      return body ? Response.json(body) : Response.json({}, { status: 404 })
    })
    vi.stubGlobal('fetch', fetchMock)
    app(`/campanhas/${ID}`)
    await userEvent.click(await screen.findByRole('button', { name: 'Excluir' }))
    const dialog = screen.getByRole('dialog', { name: 'Excluir campanha' })
    expect(within(dialog).getByRole('button', { name: 'Cancelar' })).toHaveFocus()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Excluir' }))
    const del = fetchMock.mock.calls.map(([r]) => r).find((r) => r instanceof Request && r.method === 'DELETE') as Request
    expect(del.headers.get('If-Match')).toBe('"3"')
    expect(await screen.findByTestId('loc')).toHaveTextContent('/campanhas')
  })
})

describe('fresh versions', () => {
  beforeEach(() => as(['gestor']))
  afterEach(() => vi.unstubAllGlobals())

  /** Serves the campaign from `served` (mutable) and answers writes with `writeStatus`. */
  function stub() {
    const state = { served: detail, writeStatus: 412 }
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const req = input instanceof Request ? input : new Request(new URL(String(input), 'http://api.test'), init)
      const path = new URL(req.url).pathname
      if (req.method === 'PUT') return Response.json({ data: state.served.data, meta: {} }, { status: state.writeStatus })
      if (req.method === 'DELETE') return Response.json({ status: 412 }, { status: 412 })
      if (path === `/api/v1/campaigns/${ID}`) return Response.json(state.served)
      const found = (routes as Record<string, unknown>)[path]
      return found ? Response.json(found) : Response.json({}, { status: 404 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const sent = (method: string) =>
      fetchMock.mock.calls.map(([r]) => r).filter((r): r is Request => r instanceof Request && r.method === method)
    return { state, sent }
  }
  const newer = (name: string, version: number) => ({ ...detail, data: { ...detail.data, name, version } })

  it('edit refetches on remount instead of serving the cached version', async () => {
    const { state } = stub()
    app(`/campanhas/${ID}`)
    await userEvent.click(await screen.findByRole('link', { name: 'Editar' }))
    expect(await screen.findByLabelText('Nome')).toHaveValue('Multivacinação')
    await userEvent.click(screen.getByRole('button', { name: 'Cancelar' }))
    await screen.findByText('Doses até hoje')
    state.served = newer('Outra pessoa editou', 4)
    await userEvent.click(screen.getByRole('link', { name: 'Editar' }))
    expect(await screen.findByLabelText('Nome')).toHaveValue('Outra pessoa editou')
  })

  it('the Recarregar button after a 412 resets the form to the fresh version', async () => {
    const { state, sent } = stub()
    app(`/campanhas/${ID}/editar`)
    await userEvent.type(await screen.findByLabelText('Nome'), ' 2')
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Esta campanha foi alterada por outra pessoa. Recarregue.')
    expect(sent('PUT')[0]?.headers.get('If-Match')).toBe('"3"')

    state.served = newer('Versão nova', 4)
    state.writeStatus = 200
    await userEvent.click(screen.getByRole('button', { name: 'Recarregar' }))
    expect(await screen.findByDisplayValue('Versão nova')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }))
    await screen.findByText('Doses até hoje')
    expect(sent('PUT')[1]?.headers.get('If-Match')).toBe('"4"')
  })

  it('delete sends the version captured when the dialog opened and offers a reload on 412', async () => {
    const { state, sent } = stub()
    app(`/campanhas/${ID}`)
    await userEvent.click(await screen.findByRole('button', { name: 'Excluir' }))
    state.served = newer('Renomeada', 4)
    act(() => {
      focusManager.setFocused(false)
      focusManager.setFocused(true) // focus refetch: the live detail moves ahead
    })
    await screen.findByText(/Excluir a campanha Renomeada\?/)
    const dialog = screen.getByRole('dialog', { name: 'Excluir campanha' })
    await userEvent.click(within(dialog).getByRole('button', { name: 'Excluir' }))
    expect(sent('DELETE')[0]?.headers.get('If-Match')).toBe('"3"')
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Esta campanha foi alterada por outra pessoa. Recarregue.')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Recarregar' }))
    expect(screen.queryByRole('dialog')).toBeNull()
  })
})

describe('campaign form', () => {
  beforeEach(() => as(['gestor']))
  afterEach(() => vi.unstubAllGlobals())

  function stubWrite(status: number, body: unknown) {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const req = input instanceof Request ? input : new Request(new URL(String(input), 'http://api.test'), init)
      const path = new URL(req.url).pathname
      if (req.method === 'PUT' || req.method === 'POST') return Response.json(body, { status })
      const found = (routes as Record<string, unknown>)[path]
      return found ? Response.json(found) : Response.json({}, { status: 404 })
    })
    vi.stubGlobal('fetch', fetchMock)
    return fetchMock
  }

  const writes = (m: ReturnType<typeof stubWrite>) =>
    m.mock.calls.map(([r]) => r).filter((r): r is Request => r instanceof Request && (r.method === 'PUT' || r.method === 'POST'))

  async function fillNew() {
    await userEvent.type(await screen.findByLabelText('Nome'), 'Campanha BCG')
    await userEvent.selectOptions(screen.getByLabelText('Vacina'), 'BCG')
    await userEvent.selectOptions(await screen.findByLabelText('Região'), 'BR')
    await userEvent.type(screen.getByLabelText('Início'), '2026-10-01')
    await userEvent.type(screen.getByLabelText('Fim'), '2026-10-31')
    await userEvent.type(screen.getByLabelText('Meta de doses'), '100')
  }

  it('validates on the client and sends nothing', async () => {
    const m = stubWrite(200, {})
    app('/campanhas/nova')
    await userEvent.click(await screen.findByRole('button', { name: 'Salvar' }))
    expect(screen.getByText('Informe um nome de 1 a 120 caracteres.')).toBeInTheDocument()
    expect(screen.getByText('A meta deve ser um número inteiro maior que zero.')).toBeInTheDocument()
    expect(screen.getByLabelText('Nome')).toHaveAttribute('aria-invalid', 'true')
    expect(writes(m)).toHaveLength(0)
  })

  it('creates with POST and goes to the detail page', async () => {
    const m = stubWrite(201, { data: campaign, meta: {} })
    app('/campanhas/nova')
    await fillNew()
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }))
    await screen.findByText('Doses até hoje')
    const [w] = writes(m)
    expect(w?.method).toBe('POST')
    expect(await w?.json()).toMatchObject({ name: 'Campanha BCG', vaccineCode: 'BCG', doseLabel: null, regionScope: 'BR', targetDoses: 100 })
  })

  it('maps 422 codes to field messages', async () => {
    stubWrite(422, { status: 422, code: 'unknown_vaccine_dose', field: 'doseLabel' })
    app('/campanhas/nova')
    await fillNew()
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }))
    expect(await screen.findByText('Esta combinação de vacina e dose não existe.')).toBeInTheDocument()
    expect(screen.getByLabelText('Dose')).toHaveAttribute('aria-invalid', 'true')
  })

  it('edit sends If-Match with the loaded version and shows the 412 message', async () => {
    const m = stubWrite(412, { status: 412, code: 'version_mismatch' })
    app(`/campanhas/${ID}/editar`)
    const name = await screen.findByLabelText('Nome')
    expect(name).toHaveValue('Multivacinação')
    await userEvent.type(name, ' 2')
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Esta campanha foi alterada por outra pessoa. Recarregue.')
    const [w] = writes(m)
    expect(w?.method).toBe('PUT')
    expect(w?.headers.get('If-Match')).toBe('"3"')
  })
})
