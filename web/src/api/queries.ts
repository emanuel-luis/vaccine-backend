import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect } from 'react'
import { useLive } from '../live/LiveProvider'
import { apiErrorOf, unwrap, unwrapData, useApi } from './client'
import type { AlertsQuery, Campaign, CampaignRequest, HealthUnitRow, JobKind, MapQuery, PeriodQuery } from './types'

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

/**
 * Edit form source: not live, so the version the user started from stays the one sent in If-Match.
 * Never served from cache (gcTime 0, refetch on every mount): the form must start from the server's current version.
 */
export function useCampaignForEdit(id: string) {
  return useQuery({
    ...campaignQuery(useApi(), id, ['campaigns', id]),
    refetchOnWindowFocus: false,
    refetchOnMount: 'always',
    gcTime: 0,
  })
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

// Data management (data-admin). Not under 'live': the SSE `job` status events invalidate
// ['admin', 'jobs'] and ['admin', 'job', id]; the other admin data refreshes on demand.

export function useSources() {
  const api = useApi()
  return useQuery({
    queryKey: ['admin', 'sources'],
    queryFn: async () => unwrapData(await api.GET('/api/v1/admin/sources')),
  })
}

/** Refetches when the stream (re)connects, so a status change missed while offline shows up. */
function useRefetchOnReady(refetch: () => unknown) {
  const { subscribe } = useLive()
  useEffect(() => subscribe((e) => void (e.type === 'ready' && refetch())), [subscribe, refetch])
}

export function useJobs(limit = 20) {
  const api = useApi()
  const q = useQuery({
    queryKey: ['admin', 'jobs', limit],
    queryFn: async () => unwrapData(await api.GET('/api/v1/admin/jobs', { params: { query: { limit } } })),
  })
  useRefetchOnReady(q.refetch)
  return q
}

export function useJob(id: string) {
  const api = useApi()
  const q = useQuery({
    queryKey: ['admin', 'job', id],
    queryFn: async () => unwrapData(await api.GET('/api/v1/admin/jobs/{id}', { params: { path: { id } } })),
  })
  useRefetchOnReady(q.refetch)
  return q
}

/** Throws ApiError; status 409 means a job is already queued or running. */
export function useCreateJob() {
  const api = useApi()
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (kind: JobKind) => {
      const r = await api.POST('/api/v1/admin/jobs', { body: { kind } })
      if (r.data === undefined) throw apiErrorOf(r)
      return r.data.data
    },
    onSettled: () => qc.invalidateQueries({ queryKey: ['admin', 'jobs'] }),
  })
}

/** One page of a job's log after `afterSeq` (limit 1000, the API maximum). */
export function useFetchJobLog() {
  const api = useApi()
  return useCallback(
    async (id: string, afterSeq: number) =>
      unwrapData(await api.GET('/api/v1/admin/jobs/{id}/log', { params: { path: { id }, query: { afterSeq, limit: 1000 } } })),
    [api],
  )
}

export function useDbtInvocations(limit = 20) {
  const api = useApi()
  return useQuery({
    queryKey: ['admin', 'dbt', 'invocations', limit],
    queryFn: async () => unwrapData(await api.GET('/api/v1/admin/dbt/invocations', { params: { query: { limit } } })),
  })
}

export function useDbtResults(id: string | undefined, filter: { status?: string; resourceType?: string }) {
  const api = useApi()
  return useQuery({
    queryKey: ['admin', 'dbt', 'results', id, filter],
    enabled: id !== undefined,
    queryFn: async () =>
      unwrapData(await api.GET('/api/v1/admin/dbt/invocations/{id}/results', { params: { path: { id: id! }, query: filter } })),
  })
}

export function useReconciliation(limit = 10) {
  const api = useApi()
  return useQuery({
    queryKey: ['admin', 'reconciliation', limit],
    queryFn: async () => unwrapData(await api.GET('/api/v1/admin/reconciliation', { params: { query: { limit } } })),
  })
}

export function useIngest(hours = 24) {
  const api = useApi()
  return useQuery({
    queryKey: ['admin', 'ingest', hours],
    queryFn: async () => unwrapData(await api.GET('/api/v1/admin/ingest', { params: { query: { hours } } })),
  })
}
