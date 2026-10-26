import { useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { ApiError } from '../api/client'
import { useCampaignProgress, useDeleteCampaign } from '../api/queries'
import { useSession } from '../auth/session'
import type { EChartOption } from '../charts/EChart'
import { ChartPanel } from '../components/ChartPanel'
import { ConfirmDialog } from '../components/ConfirmDialog'
import { KpiCard } from '../components/KpiCard'
import { ProgressBar } from '../components/ProgressBar'
import { StatePanel } from '../components/StatePanel'
import { STALE_MESSAGE, statusOf, zeroFill } from '../lib/campaign'
import { fmtDate, fmtDoses, isoDay, SUPPRESSED } from '../lib/format'
import { STATUS_LABEL } from './Campanhas'

export function CampanhaDetalhe() {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const { roles } = useSession()
  const q = useCampaignProgress(id)
  const del = useDeleteCampaign()
  const [confirming, setConfirming] = useState(false)
  const [deleteError, setDeleteError] = useState<string>()
  const c = q.data
  const today = isoDay(new Date())

  const days = useMemo(
    () => (c ? zeroFill(c.progress.daily, c.startsOn, c.endsOn < today ? c.endsOn : today) : []),
    [c, today],
  )
  const option = useMemo<EChartOption>(
    () => ({
      grid: { left: 8, right: 24, top: 16, bottom: 8, containLabel: true },
      tooltip: { trigger: 'axis', valueFormatter: (v: number | null) => fmtDoses(v) },
      xAxis: { type: 'category', data: days.map((d) => fmtDate(d.date)) },
      yAxis: { type: 'value' },
      series: [{ type: 'bar', data: days.map((d) => d.doses) }],
    }),
    [days],
  )

  if (q.isPending) return <StatePanel state="loading" />
  if (q.isError || !c) {
    const notFound = q.error instanceof ApiError && q.error.status === 404
    return (
      <StatePanel
        state="error"
        title={notFound ? 'Campanha não encontrada' : undefined}
        message={notFound ? 'Ela não existe ou está fora da sua região.' : 'Não foi possível carregar a campanha.'}
        onRetry={notFound ? undefined : () => void q.refetch()}
      />
    )
  }

  const p = c.progress
  const hidden = p.suppressed
  const onDelete = () =>
    del.mutate(
      { id: c.campaignId, version: c.version },
      {
        onSuccess: () => void navigate('/campanhas'),
        onError: (e) =>
          setDeleteError(
            e instanceof ApiError && e.status === 412 ? STALE_MESSAGE : 'Não foi possível excluir a campanha.',
          ),
      },
    )

  return (
    <>
      <h1>{c.name}</h1>
      <p>
        {STATUS_LABEL[statusOf(c, today)]}, {fmtDate(c.startsOn)} a {fmtDate(c.endsOn)}. Região {c.regionScope}. Vacina{' '}
        {c.vaccineCode}
        {c.doseLabel ? ` ${c.doseLabel}` : ', todas as doses'}.
      </p>
      {roles.includes('gestor') && (
        <p className="actions">
          <Link className="btn btn-link" to={`/campanhas/${c.campaignId}/editar`}>
            Editar
          </Link>
          <button
            type="button"
            className="btn"
            onClick={() => {
              setDeleteError(undefined)
              setConfirming(true)
            }}
          >
            Excluir
          </button>
        </p>
      )}
      <div className="kpi-grid">
        <KpiCard label="Doses até hoje" value={hidden ? SUPPRESSED : fmtDoses(p.dosesToDate)} />
        <KpiCard label="Meta" value={fmtDoses(c.targetDoses)} />
        <div className="kpi">
          <p className="kpi-label">Progresso</p>
          <ProgressBar pct={hidden ? null : p.pct} label="Progresso da campanha" />
        </div>
      </div>
      {hidden && (
        <p className="muted">Menos de 5 doses neste município: os valores ficam ocultos para proteger a privacidade.</p>
      )}
      <div className="chart-grid">
        <ChartPanel
          title="Doses por dia"
          summary={`Doses aplicadas por dia de ${fmtDate(c.startsOn)} até ${fmtDate(days.at(-1)?.date ?? c.startsOn)}; dias sem doses valem zero.`}
          status={days.length > 0 ? 'ready' : 'empty'}
          option={option}
          columns={['Dia', 'Doses']}
          rows={days.map((d) => [fmtDate(d.date), d.doses == null ? SUPPRESSED : fmtDoses(d.doses)])}
        />
      </div>
      {confirming && (
        <ConfirmDialog
          title="Excluir campanha"
          confirmLabel="Excluir"
          busy={del.isPending}
          onConfirm={onDelete}
          onCancel={() => setConfirming(false)}
        >
          <p>Excluir a campanha {c.name}? Esta ação não pode ser desfeita.</p>
          {deleteError && (
            <p role="alert" className="field-error">
              {deleteError}
            </p>
          )}
        </ConfirmDialog>
      )}
    </>
  )
}
