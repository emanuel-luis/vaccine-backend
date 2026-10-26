import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { mockFetch, renderApp, row } from '../test/mockApi'
import { ChoroplethMap } from './ChoroplethMap'

const canvas = vi.hoisted(() => ({ props: undefined as undefined | Record<string, unknown> }))
vi.mock('../map/MapCanvas', () => ({
  default: (props: Record<string, unknown>) => {
    canvas.props = props
    return <div data-testid="canvas" />
  },
}))

const geo = {
  type: 'FeatureCollection',
  features: ['29', '35', '43'].map((c) => ({ type: 'Feature', properties: { codarea: c }, geometry: null })),
}

const rows = [
  row({ code: '29', abbr: 'BA', name: 'Bahia', value: 96 }),
  row({ code: '35', abbr: 'SP', name: 'São Paulo', value: null, suppressed: true, reason: 'small_cell' }),
]

function setup(onSelect = vi.fn()) {
  mockFetch({ '/geo/ibge_malha_uf.geojson': geo })
  renderApp(
    <ChoroplethMap geoUrl="/geo/ibge_malha_uf.geojson" rows={rows} metric="coverage" label="Mapa" onSelect={onSelect} />,
  )
  return onSelect
}

describe('ChoroplethMap', () => {
  it('shows the legend with the suppressed entry and passes hatch states to the canvas', async () => {
    setup()
    await screen.findByTestId('canvas')
    const legend = within(screen.getByRole('list', { name: 'Legenda' }))
    expect(legend.getByText('≥ 95%')).toBeInTheDocument()
    expect(legend.getByText('Dados insuficientes (< 5)')).toBeInTheDocument()
    expect(legend.getByText('Fora do seu escopo')).toBeInTheDocument()
    const states = canvas.props?.states as Record<string, { suppressed: boolean }>
    expect(states['35']?.suppressed).toBe(true)
    expect(states['29']?.suppressed).toBe(false)
  })

  it('lists areas as the keyboard alternative, with "< 5" for suppressed ones', async () => {
    const onSelect = setup()
    const list = within(screen.getByRole('navigation', { name: 'Áreas do mapa' }))
    expect(list.getByRole('button', { name: /Bahia/ })).toHaveTextContent('96,0%')
    expect(list.getByRole('button', { name: /São Paulo/ })).toHaveTextContent('< 5')
    list.getByRole('button', { name: /Bahia/ }).focus()
    await userEvent.keyboard('{Enter}')
    expect(onSelect).toHaveBeenCalledWith(rows[0])
  })

  it('counts geometry areas without a row as outside the scope', async () => {
    setup()
    expect(await screen.findByText('1 áreas fora do seu escopo.')).toBeInTheDocument()
  })

  it('drills down on a map click for areas with data and ignores unknown codes', async () => {
    const onSelect = setup()
    await screen.findByTestId('canvas')
    const pick = canvas.props?.onPick as (c: string) => void
    pick('43')
    expect(onSelect).not.toHaveBeenCalled()
    pick('35')
    await waitFor(() => expect(onSelect).toHaveBeenCalledWith(rows[1]))
  })
})
