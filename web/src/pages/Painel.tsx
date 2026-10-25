import { useQuery } from '@tanstack/react-query'
import { ApiError, unwrap, useApi } from '../api/client'
import { KpiCard } from '../components/KpiCard'
import { StatePanel } from '../components/StatePanel'

const nf = new Intl.NumberFormat('pt-BR')

function isoDay(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

function errorMessage(error: unknown): string {
  if (error instanceof ApiError && error.status === 403) return 'Você não tem acesso a estes dados.'
  if (error instanceof ApiError && error.status === 503)
    return 'Os dados ainda não estão disponíveis. Tente novamente em instantes.'
  return 'Não foi possível carregar o resumo.'
}

export function Painel() {
  const api = useApi()
  const now = new Date()
  const from = `${now.getFullYear()}-01-01`
  const to = isoDay(now)

  // 'live' prefix: the LiveProvider invalidates these keys on SSE events.
  const summary = useQuery({
    queryKey: ['live', 'summary', from, to],
    queryFn: async () =>
      unwrap(await api.GET('/api/v1/dashboard/summary', { params: { query: { from, to } } })).data,
  })

  return (
    <>
      <h1>Visão geral</h1>
      {summary.isPending && <StatePanel state="loading" />}
      {summary.isError && (
        <StatePanel
          state="error"
          message={errorMessage(summary.error)}
          onRetry={() => void summary.refetch()}
        />
      )}
      {summary.data && (
        <div className="kpi-grid">
          <KpiCard label="Doses no ano" value={nf.format(summary.data.dosesInPeriod)} hint={`De ${from} a ${to}`} />
          <KpiCard label="Doses hoje" value={nf.format(summary.data.dosesToday)} hint={`Em ${summary.data.today}`} />
          <KpiCard
            label="Doses ao vivo pendentes"
            value={nf.format(summary.data.liveDosesPending)}
            hint={`Ainda não consolidadas na carga ${nf.format(summary.data.batchGeneration)}`}
          />
        </div>
      )}
    </>
  )
}
