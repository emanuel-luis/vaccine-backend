import { useEffect, useRef } from 'react'
import { Link } from 'react-router'
import { ApiError } from '../api/client'
import { useCreateJob, useJobs } from '../api/queries'
import { StatePanel } from '../components/StatePanel'
import { StatusBadge } from '../components/StatusBadge'
import { adminErrorMessage, isActive, JOB_KINDS, jobKindLabel, jobStatus } from '../lib/admin'
import { fmtCount, fmtDateTime, fmtDuration } from '../lib/formatTime'

export function DadosExecucoes() {
  const jobs = useJobs()
  const create = useCreateJob()
  const active = (jobs.data ?? []).some((j) => isActive(j.status))
  const busy = create.isPending || active
  const { reset } = create
  // The 409 message is stale once the active job has finished.
  const wasActive = useRef(false)
  useEffect(() => {
    if (wasActive.current && !active) reset()
    wasActive.current = active
  }, [active, reset])

  const launchError =
    create.error instanceof ApiError && create.error.status === 409
      ? 'Já existe uma execução em andamento'
      : create.isError
        ? 'Não foi possível iniciar a execução.'
        : null

  return (
    <>
      <h1>Execuções</h1>
      <div className="actions" role="group" aria-label="Iniciar execução">
        {JOB_KINDS.map((k) => (
          <button key={k.kind} type="button" className="btn btn-primary" disabled={busy} onClick={() => create.mutate(k.kind)}>
            {k.button}
          </button>
        ))}
      </div>
      {launchError && (
        <p role="alert" className="field-error">
          {launchError}
        </p>
      )}
      {jobs.isPending && <StatePanel state="loading" />}
      {jobs.isError && <StatePanel state="error" message={adminErrorMessage(jobs.error, 'as execuções')} onRetry={() => void jobs.refetch()} />}
      {jobs.data && jobs.data.length === 0 && (
        <StatePanel state="empty" title="Nenhuma execução" message="Use os botões acima para iniciar a primeira." />
      )}
      {jobs.data && jobs.data.length > 0 && (
        <div className="table-wrap">
          <table className="data-table">
            <caption>Execuções mais recentes primeiro.</caption>
            <thead>
              <tr>
                <th scope="col">Tipo</th>
                <th scope="col">Status</th>
                <th scope="col">Solicitado por</th>
                <th scope="col">Início</th>
                <th scope="col">Duração</th>
                <th scope="col" className="num">Linhas / rejeições</th>
              </tr>
            </thead>
            <tbody>
              {jobs.data.map((j) => {
                const s = jobStatus(j.status)
                return (
                  <tr key={j.jobId}>
                    <th scope="row">
                      <Link to={`/dados/execucoes/${j.jobId}`}>{jobKindLabel(j.kind)}</Link>
                    </th>
                    <td>
                      <StatusBadge tone={s.tone}>{s.label}</StatusBadge>
                    </td>
                    <td>{j.requestedBy}</td>
                    <td>{fmtDateTime(j.startedAt ?? j.requestedAt)}</td>
                    <td>{fmtDuration(j.startedAt, j.finishedAt)}</td>
                    <td className="num">
                      {fmtCount(j.rows)} / {fmtCount(j.rejects)}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}
