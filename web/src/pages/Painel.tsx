import type { UseQueryResult } from '@tanstack/react-query'
import { useMemo } from 'react'
import { ApiError } from '../api/client'
import type { PeriodQuery } from '../api/types'
import { useByAgeBand, useByVaccine, useSummary, useTimeseries, useVaccines } from '../api/queries'
import type { EChartOption } from '../charts/EChart'
import { useSession } from '../auth/session'
import { ChartPanel } from '../components/ChartPanel'
import { ExportButton } from '../components/ExportButton'
import { FilterBar } from '../components/FilterBar'
import { KpiCard } from '../components/KpiCard'
import { StatePanel } from '../components/StatePanel'
import { ageBandOf, effectiveVaccineDose, resolvePeriod, useFilters } from '../lib/filters'
import { ageBandLabel, fmtDate, fmtDoses, SUPPRESSED } from '../lib/format'

function errorMessage(error: unknown): string {
  if (error instanceof ApiError && error.status === 403) return 'Você não tem acesso a estes dados.'
  if (error instanceof ApiError && error.status === 503)
    return 'Os dados ainda não estão disponíveis. Tente novamente em instantes.'
  return 'Não foi possível carregar os dados.'
}

function statusOf(q: UseQueryResult<unknown[]>): 'loading' | 'empty' | 'error' | 'ready' {
  if (q.isPending) return 'loading'
  if (q.isError) return 'error'
  return q.data && q.data.length > 0 ? 'ready' : 'empty'
}

function granularityFor(from: string, to: string): 'day' | 'week' | 'month' {
  const days = (Date.parse(to) - Date.parse(from)) / 86_400_000
  return days <= 62 ? 'day' : days <= 400 ? 'week' : 'month'
}

/** Bars for suppressed cells are drawn at zero with a "< 5" label. */
function barOption(labels: string[], values: (number | null)[]): EChartOption {
  return {
    grid: { left: 8, right: 24, top: 8, bottom: 8, containLabel: true },
    tooltip: { trigger: 'axis', valueFormatter: (v: number) => fmtDoses(v) },
    xAxis: { type: 'value' },
    yAxis: { type: 'category', data: labels, inverse: true },
    series: [
      {
        type: 'bar',
        data: values.map((v) =>
          v == null ? { value: 0, label: { show: true, formatter: SUPPRESSED, position: 'right' } } : v,
        ),
      },
    ],
  }
}

export function Painel() {
  const [filters] = useFilters()
  const { roles } = useSession()
  const period = resolvePeriod(filters)
  const vaccines = useVaccines().data ?? []
  const { vaccine, dose } = effectiveVaccineDose(filters, vaccines, false)
  const q: PeriodQuery = { ...period, region: filters.regiao, vaccine, dose, ageBand: ageBandOf(filters) }

  const summary = useSummary(q)
  const series = useTimeseries(q, granularityFor(period.from, period.to))
  const byVaccine = useByVaccine(q)
  const byAge = useByAgeBand(q)

  const s = summary.data
  const hidden = new Set(s?.suppressedFields ?? [])
  const kpi = (field: 'dosesInPeriod' | 'dosesToday' | 'liveDosesPending') =>
    hidden.has(field) ? SUPPRESSED : fmtDoses(s?.[field])

  const seriesOption = useMemo<EChartOption>(
    () => ({
      grid: { left: 8, right: 24, top: 16, bottom: 8, containLabel: true },
      tooltip: { trigger: 'axis', valueFormatter: (v: number | null) => fmtDoses(v) },
      xAxis: { type: 'category', data: (series.data ?? []).map((p) => fmtDate(p.periodStart)) },
      yAxis: { type: 'value' },
      series: [{ type: 'line', showSymbol: false, connectNulls: false, data: (series.data ?? []).map((p) => (p.suppressed ? null : p.doses)) }],
    }),
    [series.data],
  )
  const vaccineRows = useMemo(() => byVaccine.data ?? [], [byVaccine.data])
  const vaccineOption = useMemo(
    () => barOption(vaccineRows.map((r) => `${r.vaccineName} ${r.doseLabel}`), vaccineRows.map((r) => (r.suppressed ? null : (r.doses ?? null)))),
    [vaccineRows],
  )
  const ageRows = useMemo(() => byAge.data ?? [], [byAge.data])
  const ageOption = useMemo(
    () => barOption(ageRows.map((r) => ageBandLabel(r.ageBandYears)), ageRows.map((r) => (r.suppressed ? null : (r.doses ?? null)))),
    [ageRows],
  )

  const range = `${fmtDate(period.from)} a ${fmtDate(period.to)}`

  return (
    <>
      <h1>Visão geral</h1>
      <FilterBar />
      {roles.includes('gestor') && (
        <p>
          <ExportButton
            dataset="doses-daily"
            query={{
              from: q.from,
              to: q.to,
              region: q.region,
              vaccine: q.vaccine,
              dose: q.dose,
              ageBand: q.ageBand === undefined ? undefined : String(q.ageBand),
            }}
          />
        </p>
      )}
      {summary.isPending && <StatePanel state="loading" />}
      {summary.isError && (
        <StatePanel state="error" message={errorMessage(summary.error)} onRetry={() => void summary.refetch()} />
      )}
      {s && (
        <div className="kpi-grid">
          <KpiCard label="Doses no período" value={kpi('dosesInPeriod')} hint={range} />
          <KpiCard label="Doses hoje" value={kpi('dosesToday')} hint={`Em ${fmtDate(s.today)}`} />
          <KpiCard
            label="Doses ao vivo pendentes"
            value={kpi('liveDosesPending')}
            hint={`Ainda não consolidadas na carga ${s.batchGeneration}`}
          />
        </div>
      )}
      <div className="chart-grid">
        <ChartPanel
          title="Doses ao longo do tempo"
          summary={`Doses aplicadas de ${range}, agrupadas por ${granularityFor(period.from, period.to) === 'day' ? 'dia' : granularityFor(period.from, period.to) === 'week' ? 'semana' : 'mês'}.`}
          status={statusOf(series)}
          option={seriesOption}
          columns={['Período', 'Doses']}
          rows={(series.data ?? []).map((p) => [fmtDate(p.periodStart), p.suppressed ? SUPPRESSED : fmtDoses(p.doses)])}
          errorMessage={errorMessage(series.error)}
          onRetry={() => void series.refetch()}
        />
        <ChartPanel
          title="Doses por vacina"
          summary={`Doses aplicadas por vacina e dose, de ${range}.`}
          status={statusOf(byVaccine)}
          option={vaccineOption}
          columns={['Vacina', 'Dose', 'Doses']}
          rows={vaccineRows.map((r) => [r.vaccineName, r.doseLabel, r.suppressed ? SUPPRESSED : fmtDoses(r.doses)])}
          errorMessage={errorMessage(byVaccine.error)}
          onRetry={() => void byVaccine.refetch()}
        />
        <ChartPanel
          title="Doses por faixa etária"
          summary={`Doses aplicadas por faixa etária, de ${range}.`}
          status={statusOf(byAge)}
          option={ageOption}
          columns={['Faixa etária', 'Doses']}
          rows={ageRows.map((r) => [ageBandLabel(r.ageBandYears), r.suppressed ? SUPPRESSED : fmtDoses(r.doses)])}
          errorMessage={errorMessage(byAge.error)}
          onRetry={() => void byAge.refetch()}
        />
      </div>
    </>
  )
}
