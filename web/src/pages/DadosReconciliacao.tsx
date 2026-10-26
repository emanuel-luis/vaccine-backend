import { useMemo } from 'react'
import { useIngest, useReconciliation } from '../api/queries'
import { ChartPanel } from '../components/ChartPanel'
import { StatePanel } from '../components/StatePanel'
import { StatusBadge } from '../components/StatusBadge'
import { adminErrorMessage, jobStatus, parseDiffKey, zeroFillHours } from '../lib/admin'
import { fmtCount, fmtDateTime, fmtHour } from '../lib/formatTime'

function Reconciliations() {
  const q = useReconciliation()
  if (q.isPending) return <StatePanel state="loading" />
  if (q.isError) return <StatePanel state="error" message={adminErrorMessage(q.error, 'as reconciliações')} onRetry={() => void q.refetch()} />
  if (q.data.length === 0)
    return <StatePanel state="empty" title="Nenhuma reconciliação" message="Rode Reconciliar em Execuções para conferir os totais." />
  return (
    <>
      {q.data.map((run, i) => (
        <details key={run.runId} open={i === 0} className="recon-run">
          <summary>
            {fmtDateTime(run.startedAt)} <StatusBadge tone={jobStatus(run.status).tone}>{jobStatus(run.status).label}</StatusBadge>
          </summary>
          <div className="table-wrap">
            <table className="data-table">
              <caption className="sr-only">Verificações da reconciliação de {fmtDateTime(run.startedAt)}</caption>
              <thead>
                <tr>
                  <th scope="col">Verificação</th>
                  <th scope="col">Resultado</th>
                  <th scope="col" className="num">Esperado</th>
                  <th scope="col" className="num">Obtido</th>
                  <th scope="col" className="num">Chaves divergentes</th>
                </tr>
              </thead>
              <tbody>
                {run.checks.map((c) => (
                  <tr key={c.checkName}>
                    <th scope="row">{c.checkName}</th>
                    <td>
                      <StatusBadge tone={c.passed ? 'ok' : 'err'}>{c.passed ? 'Aprovado' : 'Reprovado'}</StatusBadge>
                    </td>
                    <td className="num">{fmtCount(c.expectedTotal)}</td>
                    <td className="num">{fmtCount(c.actualTotal)}</td>
                    <td className="num">
                      {fmtCount(c.keysMismatched)} de {fmtCount(c.keysCompared)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {run.checks
            .filter((c) => c.diffs.length > 0)
            .map((c) => (
              <div key={c.checkName} className="table-wrap">
                <table className="data-table">
                  <caption>Divergências de {c.checkName}</caption>
                  <thead>
                    <tr>
                      <th scope="col">Chave</th>
                      <th scope="col" className="num">Esperado</th>
                      <th scope="col" className="num">Obtido</th>
                    </tr>
                  </thead>
                  <tbody>
                    {c.diffs.map((d) => (
                      <tr key={d.lineNo}>
                        <th scope="row">{parseDiffKey(d.diffKey)}</th>
                        <td className="num">{fmtCount(d.expected)}</td>
                        <td className="num">{fmtCount(d.actual)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ))}
        </details>
      ))}
    </>
  )
}

function IngestSection() {
  const q = useIngest(24)
  // dataUpdatedAt is the fetch time: the 24 h window needs no clock read during render.
  const filled = useMemo(() => (q.data ? zeroFillHours(q.data.acceptedPerHour, q.dataUpdatedAt) : []), [q.data, q.dataUpdatedAt])
  const labels = filled.map((p) => fmtHour(p.hour))
  const option = {
    grid: { left: 56, right: 16, top: 16, bottom: 32 },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: labels },
    yAxis: { type: 'value', minInterval: 1 },
    series: [{ type: 'bar', data: filled.map((p) => p.doses) }],
  }
  const status = q.isPending ? 'loading' : q.isError ? 'error' : q.data.acceptedPerHour.length === 0 ? 'empty' : 'ready'
  const rejects = q.data?.rejectsByReason ?? []

  return (
    <>
      <ChartPanel
        title="Doses aceitas por hora (últimas 24 h)"
        summary="Gráfico de barras com as doses aceitas pela API em cada uma das últimas 24 horas."
        status={status}
        option={option}
        columns={['Hora', 'Doses aceitas']}
        rows={filled.map((p, i) => [labels[i]!, fmtCount(p.doses)])}
        errorMessage={adminErrorMessage(q.error, 'a ingestão')}
        onRetry={() => void q.refetch()}
      />
      <h2>Rejeições por motivo</h2>
      {q.data && rejects.length === 0 && <StatePanel state="empty" title="Nenhuma rejeição" message="Nenhuma dose foi rejeitada no período." />}
      {rejects.length > 0 && (
        <div className="table-wrap">
          <table className="data-table">
            <caption className="sr-only">Doses rejeitadas por motivo</caption>
            <thead>
              <tr>
                <th scope="col">Motivo</th>
                <th scope="col" className="num">HTTP</th>
                <th scope="col" className="num">Quantidade</th>
              </tr>
            </thead>
            <tbody>
              {rejects.map((r) => (
                <tr key={`${r.reason}-${r.httpStatus}`}>
                  <th scope="row">{r.reason}</th>
                  <td className="num">{r.httpStatus}</td>
                  <td className="num">{fmtCount(r.count)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}

export function DadosReconciliacao() {
  return (
    <>
      <h1>Reconciliação e ingestão</h1>
      <h2>Reconciliações recentes</h2>
      <Reconciliations />
      <IngestSection />
    </>
  )
}
