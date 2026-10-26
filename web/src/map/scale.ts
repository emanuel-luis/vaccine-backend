import type { MapRow } from '../api/types'
import { fmtDoses, fmtPct, SUPPRESSED } from '../lib/format'

export type Metric = 'coverage' | 'doses'
export type AreaStatus = 'value' | 'suppressed' | 'nodata' | 'outside'

/** ColorBrewer YlGnBu, 5 classes: sequential and colorblind-safe. */
export const CLASS_COLORS = ['#ffffcc', '#a1dab4', '#41b6c4', '#2c7fb8', '#253494'] as const
export const OUTSIDE_COLOR = '#9aa3b2'
export const NODATA_COLOR = '#e3e6eb'

/** Coverage class limits in percent (95% is the usual vaccination goal). */
export const COVERAGE_BREAKS = [50, 70, 85, 95]

/** The API only returns rows inside the caller's scope: no row means "fora do seu escopo". */
export function areaStatus(row: MapRow | undefined): AreaStatus {
  if (!row) return 'outside'
  if (row.suppressed) return 'suppressed'
  return row.value == null ? 'nodata' : 'value'
}

/** Doses breaks: quantiles (20/40/60/80%) of the visible values, rounded and de-duplicated. */
export function dosesBreaks(values: number[]): number[] {
  const v = values.filter((x) => Number.isFinite(x)).sort((a, b) => a - b)
  if (v.length === 0) return []
  const out = new Set<number>()
  for (const q of [0.2, 0.4, 0.6, 0.8]) out.add(Math.round(v[Math.min(v.length - 1, Math.floor(q * v.length))] ?? 0))
  return [...out].sort((a, b) => a - b)
}

export function breaksFor(metric: Metric, rows: MapRow[]): number[] {
  if (metric === 'coverage') return COVERAGE_BREAKS
  return dosesBreaks(rows.filter((r) => areaStatus(r) === 'value').map((r) => r.value as number))
}

/** Class index 0..breaks.length; a value equal to a break goes to the upper class. */
export function classIndex(value: number, breaks: number[]): number {
  let i = 0
  while (i < breaks.length && value >= (breaks[i] as number)) i++
  return i
}

export function classColor(index: number, classes: number): string {
  if (classes <= 1) return CLASS_COLORS[2]
  return CLASS_COLORS[Math.round((index * (CLASS_COLORS.length - 1)) / (classes - 1))] ?? CLASS_COLORS[0]
}

export interface AreaState {
  color: string
  suppressed: boolean
}

/** Feature-state per area code. Areas without a row get no state (the layer falls back to grey). */
export function areaStates(rows: MapRow[], breaks: number[]): Record<string, AreaState> {
  const out: Record<string, AreaState> = {}
  for (const r of rows) {
    if (r.code == null) continue
    const status = areaStatus(r)
    if (status === 'value')
      out[r.code] = { color: classColor(classIndex(r.value as number, breaks), breaks.length + 1), suppressed: false }
    else out[r.code] = { color: NODATA_COLOR, suppressed: status === 'suppressed' }
  }
  return out
}

export interface LegendItem {
  kind: 'class' | 'suppressed' | 'nodata' | 'outside'
  color?: string
  label: string
}

export function legendItems(metric: Metric, breaks: number[]): LegendItem[] {
  const fmt = metric === 'coverage' ? (n: number) => `${n}%` : (n: number) => fmtDoses(n)
  const items: LegendItem[] = []
  if (breaks.length > 0) {
    const n = breaks.length + 1
    for (let i = 0; i < n; i++) {
      const lo = breaks[i - 1]
      const hi = breaks[i]
      const label =
        lo === undefined ? `< ${fmt(hi as number)}` : hi === undefined ? `≥ ${fmt(lo)}` : `${fmt(lo)} a ${fmt(hi)}`
      items.push({ kind: 'class', color: classColor(i, n), label })
    }
  }
  items.push({ kind: 'suppressed', label: `Dados insuficientes (${SUPPRESSED})` })
  items.push({ kind: 'nodata', color: NODATA_COLOR, label: 'Sem dados' })
  items.push({ kind: 'outside', color: OUTSIDE_COLOR, label: 'Fora do seu escopo' })
  return items
}

/** Text for an area in the list and tooltip. */
export function areaText(row: MapRow, metric: Metric): string {
  const status = areaStatus(row)
  if (status === 'suppressed') return `${SUPPRESSED} (dados insuficientes)`
  if (status === 'nodata') return row.reason === 'no_population' ? 'Sem população de referência' : 'Sem dados'
  return metric === 'coverage' ? fmtPct(row.value) : fmtDoses(row.value)
}

type Position = number | Position[]

/** Bounding box [west, south, east, north] of a GeoJSON feature collection. */
export function bbox(geo: {
  features: { geometry: { coordinates: Position } | null }[]
}): [number, number, number, number] {
  let w = Infinity
  let s = Infinity
  let e = -Infinity
  let n = -Infinity
  const walk = (c: Position) => {
    if (typeof c === 'number') return
    if (typeof c[0] === 'number') {
      const [x, y] = c as number[]
      w = Math.min(w, x as number)
      e = Math.max(e, x as number)
      s = Math.min(s, y as number)
      n = Math.max(n, y as number)
    } else for (const child of c) walk(child)
  }
  for (const f of geo.features) if (f.geometry) walk(f.geometry.coordinates)
  return [w, s, e, n]
}

/** View for the map: the selected area when it is in the geometry, otherwise everything. */
export function viewBbox(
  geo: { features: { properties: { codarea: string }; geometry: { coordinates: Position } | null }[] },
  code?: string,
): [number, number, number, number] {
  const hit = code ? geo.features.filter((f) => f.properties.codarea === code) : []
  return bbox({ features: hit.length > 0 ? hit : geo.features })
}

/** fitBounds options: zoom in on a selection (animated unless the user prefers reduced motion). */
export function fitOptions(selected: boolean, reducedMotion: boolean): { padding: number; animate: boolean } {
  return { padding: selected ? 48 : 16, animate: selected && !reducedMotion }
}
