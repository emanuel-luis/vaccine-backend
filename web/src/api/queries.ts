import { useQuery } from '@tanstack/react-query'
import { unwrap, unwrapData, useApi } from './client'
import type { HealthUnitRow, MapQuery, PeriodQuery } from './types'

// The 'live' key prefix is what the SSE LiveProvider invalidates (summary and timeseries only).

export function useVaccines() {
  const api = useApi()
  return useQuery({
    queryKey: ['ref', 'vaccines'],
    staleTime: Infinity,
    queryFn: async () => unwrapData(await api.GET('/api/v1/reference/vaccines')),
  })
}

export function useRegions(state?: string, enabled = true) {
  const api = useApi()
  return useQuery({
    queryKey: ['ref', 'regions', state ?? 'all'],
    staleTime: Infinity,
    enabled,
    queryFn: async () => unwrapData(await api.GET('/api/v1/reference/regions', { params: { query: { state } } })),
  })
}

export function useSummary(q: PeriodQuery) {
  const api = useApi()
  return useQuery({
    queryKey: ['live', 'summary', q],
    queryFn: async () => {
      return unwrapData(await api.GET('/api/v1/dashboard/summary', { params: { query: q } }))
    },
  })
}

export function useTimeseries(q: PeriodQuery, granularity: 'day' | 'week' | 'month') {
  const api = useApi()
  return useQuery({
    queryKey: ['live', 'timeseries', q, granularity],
    queryFn: async () => {
      const r = unwrap(await api.GET('/api/v1/dashboard/doses/timeseries', { params: { query: { ...q, granularity } } }))
      return r.data
    },
  })
}

export function useByVaccine(q: PeriodQuery) {
  const api = useApi()
  const query = { from: q.from, to: q.to, region: q.region, ageBand: q.ageBand }
  return useQuery({
    queryKey: ['dash', 'by-vaccine', query],
    queryFn: async () => unwrapData(await api.GET('/api/v1/dashboard/doses/by-vaccine', { params: { query } })),
  })
}

export function useByAgeBand(q: PeriodQuery) {
  const api = useApi()
  const query = { from: q.from, to: q.to, region: q.region, vaccine: q.vaccine, dose: q.dose }
  return useQuery({
    queryKey: ['dash', 'by-age-band', query],
    queryFn: async () => unwrapData(await api.GET('/api/v1/dashboard/doses/by-age-band', { params: { query } })),
  })
}

export function useMapData(q: MapQuery, enabled = true) {
  const api = useApi()
  return useQuery({
    queryKey: ['dash', 'map', q],
    enabled,
    queryFn: async () => unwrap(await api.GET('/api/v1/dashboard/map', { params: { query: q } })),
  })
}

export function useHealthUnits(q: PeriodQuery & { municipality: string }, enabled = true) {
  const api = useApi()
  return useQuery({
    queryKey: ['dash', 'health-units', q],
    enabled,
    queryFn: async (): Promise<HealthUnitRow[]> =>
      unwrapData(await api.GET('/api/v1/dashboard/health-units', { params: { query: q } })),
  })
}
