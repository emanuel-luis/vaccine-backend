import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiErrorOf, unwrap, unwrapData, useApi } from './client'
import type { AlertsQuery, Campaign, CampaignRequest, HealthUnitRow, MapQuery, PeriodQuery } from './types'

// The 'live' key prefix is what the SSE LiveProvider invalidates (every dashboard query).

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
    queryKey: ['live', 'by-vaccine', query],
    queryFn: async () => unwrapData(await api.GET('/api/v1/dashboard/doses/by-vaccine', { params: { query } })),
  })
}

export function useByAgeBand(q: PeriodQuery) {
  const api = useApi()
  const query = { from: q.from, to: q.to, region: q.region, vaccine: q.vaccine, dose: q.dose }
  return useQuery({
    queryKey: ['live', 'by-age-band', query],
    queryFn: async () => unwrapData(await api.GET('/api/v1/dashboard/doses/by-age-band', { params: { query } })),
  })
}

export function useMapData(q: MapQuery, enabled = true) {
  const api = useApi()
  return useQuery({
    queryKey: ['live', 'map', q],
    enabled,
    queryFn: async () => unwrap(await api.GET('/api/v1/dashboard/map', { params: { query: q } })),
  })
}

export function useHealthUnits(q: PeriodQuery & { municipality: string }, enabled = true) {
  const api = useApi()
  return useQuery({
    queryKey: ['live', 'health-units', q],
    enabled,
    queryFn: async (): Promise<HealthUnitRow[]> =>
      unwrapData(await api.GET('/api/v1/dashboard/health-units', { params: { query: q } })),
  })
}

export function useAlerts(q: AlertsQuery, enabled = true) {
  const api = useApi()
  return useQuery({
    queryKey: ['alerts', q],
    enabled,
    queryFn: async () => unwrapData(await api.GET('/api/v1/alerts/low-coverage', { params: { query: q } })),
  })
}

export function useCampaigns() {
  const api = useApi()
  return useQuery({
    queryKey: ['campaigns'],
    queryFn: async () => unwrapData(await api.GET('/api/v1/campaigns', { params: { query: {} } })),
  })
}

const campaignQuery = (api: ReturnType<typeof useApi>, id: string, key: unknown[]) => ({
  queryKey: key,
  queryFn: async () => unwrap(await api.GET('/api/v1/campaigns/{id}', { params: { path: { id } } })).data,
})

/** Progress changes with live doses, so the detail lives under the 'live' prefix. */
export function useCampaignProgress(id: string) {
  return useQuery(campaignQuery(useApi(), id, ['live', 'campaign', id]))
}

/** One detail query per campaign, for the progress bars of the list. */
export function useCampaignProgressList(campaigns: Campaign[]) {
  const api = useApi()
  return useQueries({ queries: campaigns.map((c) => campaignQuery(api, c.campaignId, ['live', 'campaign', c.campaignId])) })
}

/** Edit form source: not live, so the version the user started from stays the one sent in If-Match. */
export function useCampaignForEdit(id: string) {
  return useQuery({ ...campaignQuery(useApi(), id, ['campaigns', id]), refetchOnWindowFocus: false, staleTime: Infinity })
}

/** Create (no id) or update (id + version for If-Match). Throws ApiError with status, code and field. */
export function useSaveCampaign() {
  const api = useApi()
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (v: { body: CampaignRequest; id?: string; version?: number }) => {
      const r = v.id
        ? await api.PUT('/api/v1/campaigns/{id}', {
            params: { path: { id: v.id }, header: { 'If-Match': `"${v.version}"` } },
            body: v.body,
          })
        : await api.POST('/api/v1/campaigns', { body: v.body })
      if (!r.response.ok || r.data === undefined) throw apiErrorOf(r)
      return r.data.data
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ['campaigns'] }).then(() => qc.invalidateQueries({ queryKey: ['live', 'campaign'] })),
  })
}

export function useDeleteCampaign() {
  const api = useApi()
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (v: { id: string; version: number }) => {
      const r = await api.DELETE('/api/v1/campaigns/{id}', {
        params: { path: { id: v.id }, header: { 'If-Match': `"${v.version}"` } },
      })
      if (!r.response.ok) throw apiErrorOf(r)
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ['campaigns'] }),
  })
}
