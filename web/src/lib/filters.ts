import { useCallback, useMemo } from 'react'
import { useSearchParams } from 'react-router'
import type { Vaccine } from '../api/types'
import { isoDay } from './format'

/** URL search params owned by the filter bar. */
export const FILTER_KEYS = ['de', 'ate', 'ano', 'regiao', 'vacina', 'dose', 'faixa'] as const
export type FilterKey = (typeof FILTER_KEYS)[number]
export type FilterState = Partial<Record<FilterKey, string>>

export function readFilters(params: URLSearchParams): FilterState {
  const out: FilterState = {}
  for (const k of FILTER_KEYS) {
    const v = params.get(k)
    if (v) out[k] = v
  }
  return out
}

/** Merges a patch into the params; empty or undefined values delete the key. Other keys are kept. */
export function patchParams(params: URLSearchParams, patch: FilterState): URLSearchParams {
  const next = new URLSearchParams(params)
  for (const [k, v] of Object.entries(patch)) {
    if (v) next.set(k, v)
    else next.delete(k)
  }
  return next
}

export function useFilters(): [FilterState, (patch: FilterState) => void, URLSearchParams] {
  const [params, setParams] = useSearchParams()
  const filters = useMemo(() => readFilters(params), [params])
  const update = useCallback((patch: FilterState) => setParams((p) => patchParams(p, patch)), [setParams])
  return [filters, update, params]
}

const DATE = /^\d{4}-\d{2}-\d{2}$/

/** Period for dose queries: explicit de/ate, else the chosen year, else the current year to date. */
export function resolvePeriod(f: FilterState, now: Date = new Date()): { from: string; to: string } {
  if (f.de && f.ate && DATE.test(f.de) && DATE.test(f.ate)) return { from: f.de, to: f.ate }
  const year = f.ano && /^\d{4}$/.test(f.ano) ? Number(f.ano) : now.getFullYear()
  const end = year === now.getFullYear() ? isoDay(now) : `${year}-12-31`
  return { from: `${year}-01-01`, to: end }
}

export function ageBandOf(f: FilterState): number | undefined {
  const n = f.faixa === undefined ? NaN : Number(f.faixa)
  return Number.isInteger(n) && n >= 0 && n <= 6 ? n : undefined
}

/** Vaccine and dose to query with. In indicator mode (coverage) the default is the first indicator dose. */
export function effectiveVaccineDose(
  f: FilterState,
  vaccines: Vaccine[],
  indicatorOnly: boolean,
): { vaccine?: string; dose?: string } {
  if (!indicatorOnly) return { vaccine: f.vacina, dose: f.vacina ? f.dose : undefined }
  const indicators = vaccines.filter((v) => v.isCoverageIndicator)
  const pick =
    indicators.find((v) => v.vaccineCode === f.vacina && (!f.dose || v.doseLabel === f.dose)) ?? indicators[0]
  return pick ? { vaccine: pick.vaccineCode, dose: pick.doseLabel } : {}
}
