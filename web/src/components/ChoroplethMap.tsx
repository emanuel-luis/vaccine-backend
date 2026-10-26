import { useQuery } from '@tanstack/react-query'
import { lazy, Suspense, useMemo, useState } from 'react'
import type { MapRow } from '../api/types'
import type { Geo } from '../map/MapCanvas'
import { areaStates, areaStatus, areaText, breaksFor, legendItems, type Metric } from '../map/scale'
import { StatePanel } from './StatePanel'

// MapLibre is a separate lazy chunk: it never loads for screens without a map.
const MapCanvas = lazy(() => import('../map/MapCanvas'))

export interface ChoroplethMapProps {
  geoUrl: string
  rows: MapRow[]
  metric: Metric
  label: string
  selectedCode?: string
  onSelect: (row: MapRow) => void
}

function useGeo(url: string) {
  return useQuery({
    queryKey: ['geo', url],
    staleTime: Infinity,
    queryFn: async (): Promise<Geo> => {
      const res = await fetch(url)
      if (!res.ok) throw new Error(`geo: HTTP ${res.status}`)
      return (await res.json()) as Geo
    },
  })
}

export function Legend({ metric, breaks }: { metric: Metric; breaks: number[] }) {
  return (
    <ul className="legend" aria-label="Legenda">
      {legendItems(metric, breaks).map((i) => (
        <li key={i.label}>
          <span
            className={i.kind === 'suppressed' ? 'swatch swatch-hatch' : 'swatch'}
            style={i.color ? { background: i.color } : undefined}
            aria-hidden="true"
          />
          {i.label}
        </li>
      ))}
    </ul>
  )
}

export function ChoroplethMap({ geoUrl, rows, metric, label, selectedCode, onSelect }: ChoroplethMapProps) {
  const geo = useGeo(geoUrl)
  const [hover, setHover] = useState<string | null>(null)
  const breaks = useMemo(() => breaksFor(metric, rows), [metric, rows])
  const states = useMemo(() => areaStates(rows, breaks), [rows, breaks])
  const byCode = useMemo(() => new Map(rows.map((r) => [r.code, r])), [rows])
  const sorted = useMemo(() => [...rows].sort((a, b) => a.name.localeCompare(b.name, 'pt-BR')), [rows])
  const outside = geo.data ? geo.data.features.filter((f) => !byCode.has(f.properties.codarea)).length : 0
  const hovered = hover ? byCode.get(hover) : undefined

  const pick = (code: string) => {
    const row = byCode.get(code)
    if (row && areaStatus(row) !== 'outside') onSelect(row)
  }

  return (
    <div className="map-layout">
      <div>
        {geo.isPending && <StatePanel state="loading" />}
        {geo.isError && <StatePanel state="error" message="Não foi possível carregar o mapa." onRetry={() => void geo.refetch()} />}
        {geo.data && (
          <Suspense fallback={<StatePanel state="loading" />}>
            <MapCanvas
              geo={geo.data}
              states={states}
              selectedCode={selectedCode}
              label={label}
              onPick={pick}
              onHover={setHover}
            />
          </Suspense>
        )}
        <p className="map-hover" aria-hidden="true">
          {hovered ? `${hovered.name}: ${areaText(hovered, metric)}` : hover ? 'Fora do seu escopo' : ' '}
        </p>
        <Legend metric={metric} breaks={breaks} />
      </div>
      <nav aria-label="Áreas do mapa" className="area-list">
        <h3>Áreas</h3>
        <ul>
          {sorted.map((r) => (
            <li key={r.code}>
              <button
                type="button"
                className="area-item"
                aria-current={r.code === selectedCode ? 'true' : undefined}
                onClick={() => onSelect(r)}
              >
                <span>{r.name}</span>
                <span className="area-value">{areaText(r, metric)}</span>
              </button>
            </li>
          ))}
        </ul>
        {outside > 0 && <p className="muted">{outside} áreas fora do seu escopo.</p>}
      </nav>
    </div>
  )
}
