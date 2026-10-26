import { useState } from 'react'
import { useDbtInvocations, useDbtResults } from '../api/queries'
import { KpiCard } from '../components/KpiCard'
import { StatePanel } from '../components/StatePanel'
import { StatusBadge } from '../components/StatusBadge'
import { adminErrorMessage, DBT_STATUSES, DBT_TYPES, dbtStatus } from '../lib/admin'
import { fmtCount, fmtDateTime, fmtSeconds } from '../lib/formatTime'

export function DadosTestes() {
  const invocations = useDbtInvocations()
  const [picked, setPicked] = useState('')
  const [status, setStatus] = useState('')
  const [type, setType] = useState('')

  const list = invocations.data ?? []
  const current = list.find((i) => i.invocationId === picked) ?? list[0]
  const results = useDbtResults(current?.invocationId, { status: status || undefined, resourceType: type || undefined })

  return (
    <>
      <h1>Testes de qualidade (dbt)</h1>
      {invocations.isPending && <StatePanel state="loading" />}
      {invocations.isError && (
        <StatePanel state="error" message={adminErrorMessage(invocations.error, 'as execuções do dbt')} onRetry={() => void invocations.refetch()} />
      )}
      {invocations.data && list.length === 0 && (
        <StatePanel state="empty" title="Nenhuma execução do dbt" message="Rode dbt build em Execuções para ver os resultados." />
      )}
      {current && (
        <>
          <form className="filter-form" aria-label="Escolher execução" onSubmit={(e) => e.preventDefault()}>
            <label>
              Execução
              <select value={current.invocationId} onChange={(e) => setPicked(e.target.value)}>
                {list.map((i) => (
                  <option key={i.invocationId} value={i.invocationId}>
                    {fmtDateTime(i.generatedAt)} · {i.command}
                  </option>
                ))}
              </select>
            </label>
          </form>
          <div className="kpi-grid">
            <KpiCard label="Comando" value={current.command} hint={`dbt ${current.dbtVersion}`} />
            <KpiCard label="Quando" value={fmtDateTime(current.generatedAt)} />
            <KpiCard label="Duração" value={fmtSeconds(current.elapsedS)} />
            <KpiCard label="Resultado" value={current.success ? 'Sucesso' : 'Falhou'} />
            <KpiCard label="Geração absorvida" value={current.batchGeneration == null ? '—' : String(current.batchGeneration)} />
          </div>
          <form className="filter-form" aria-label="Filtros dos resultados" onSubmit={(e) => e.preventDefault()}>
            <label>
              Status
              <select value={status} onChange={(e) => setStatus(e.target.value)}>
                <option value="">Todos</option>
                {DBT_STATUSES.map((s) => (
                  <option key={s} value={s}>
                    {dbtStatus(s).label}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Tipo
              <select value={type} onChange={(e) => setType(e.target.value)}>
                <option value="">Todos</option>
                {DBT_TYPES.map((t) => (
                  <option key={t.value} value={t.value}>
                    {t.label}
                  </option>
                ))}
              </select>
            </label>
          </form>
          {results.isPending && <StatePanel state="loading" />}
          {results.isError && (
            <StatePanel state="error" message={adminErrorMessage(results.error, 'os resultados')} onRetry={() => void results.refetch()} />
          )}
          {results.data && results.data.length === 0 && <StatePanel state="empty" message="Nenhum resultado para os filtros escolhidos." />}
          {results.data && results.data.length > 0 && (
            <div className="table-wrap">
              <table className="data-table">
                <caption>Resultados por nó, {results.data.length} no total.</caption>
                <thead>
                  <tr>
                    <th scope="col">Nó</th>
                    <th scope="col">Tipo</th>
                    <th scope="col">Status</th>
                    <th scope="col" className="num">Falhas</th>
                    <th scope="col" className="num">Tempo</th>
                    <th scope="col">Mensagem</th>
                  </tr>
                </thead>
                <tbody>
                  {results.data.map((r) => {
                    const s = dbtStatus(r.status)
                    return (
                      <tr key={r.uniqueId}>
                        <th scope="row" title={r.uniqueId}>
                          {r.name}
                        </th>
                        <td>{r.resourceType}</td>
                        <td>
                          <StatusBadge tone={s.tone}>{s.label}</StatusBadge>
                        </td>
                        <td className="num">{fmtCount(r.failures)}</td>
                        <td className="num">{fmtSeconds(r.executionTimeS)}</td>
                        <td>{r.message ?? '—'}</td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </>
  )
}
