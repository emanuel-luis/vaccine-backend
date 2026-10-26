import { useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router'
import { ApiError } from '../api/client'
import { useJob } from '../api/queries'
import { StatePanel } from '../components/StatePanel'
import { StatusBadge } from '../components/StatusBadge'
import { adminErrorMessage, jobKindLabel, jobStatus } from '../lib/admin'
import { fmtCount, fmtDateTime, fmtDuration } from '../lib/formatTime'
import { useJobLog } from '../live/useJobLog'

const LEVELS: Record<string, string> = { info: 'INFO', warn: 'AVISO', error: 'ERRO' }
const time = new Intl.DateTimeFormat('pt-BR', { timeStyle: 'medium' })

function JobLog({ jobId }: { jobId: string }) {
  const { lines, loaded, failed, retry } = useJobLog(jobId)
  const [paused, setPaused] = useState(false)
  const box = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const el = box.current
    if (el && !paused) el.scrollTop = el.scrollHeight
  }, [lines.length, paused])

  return (
    <section aria-labelledby="log-title">
      <div className="panel-head">
        <h2 id="log-title">Registro</h2>
        <button type="button" className="btn" aria-pressed={paused} onClick={() => setPaused((p) => !p)}>
          Pausar rolagem
        </button>
      </div>
      {failed && <StatePanel state="error" message="Não foi possível carregar o registro." onRetry={() => void retry()} />}
      <div ref={box} className="job-log" role="log" aria-live="polite" aria-label="Registro da execução" tabIndex={0}>
        {loaded && !failed && lines.length === 0 && <p className="muted">Ainda não há linhas de registro.</p>}
        {lines.map((l) => (
          <div key={l.seq} className={`log-line log-${l.level}`}>
            <time dateTime={l.loggedAt}>{time.format(new Date(l.loggedAt))}</time>
            <span className="log-level">{LEVELS[l.level] ?? l.level.toUpperCase()}</span>
            <span className="log-message">{l.message}</span>
          </div>
        ))}
      </div>
    </section>
  )
}

export function DadosExecucaoDetalhe() {
  const { jobId = '' } = useParams()
  const q = useJob(jobId)
  const j = q.data

  return (
    <>
      <p>
        <Link to="/dados/execucoes">Voltar para execuções</Link>
      </p>
      <h1>{j ? jobKindLabel(j.kind) : 'Execução'}</h1>
      {q.isPending && <StatePanel state="loading" />}
      {q.isError &&
        (q.error instanceof ApiError && q.error.status === 404 ? (
          <StatePanel state="empty" title="Execução não encontrada" message="Ela pode ter sido removida." />
        ) : (
          <StatePanel state="error" message={adminErrorMessage(q.error, 'a execução')} onRetry={() => void q.refetch()} />
        ))}
      {j && (
        <>
          <dl className="detail-list">
            <dt>Status</dt>
            <dd>
              <StatusBadge tone={jobStatus(j.status).tone}>{jobStatus(j.status).label}</StatusBadge>
            </dd>
            <dt>Solicitado por</dt>
            <dd>{j.requestedBy}</dd>
            <dt>Solicitado em</dt>
            <dd>{fmtDateTime(j.requestedAt)}</dd>
            <dt>Início</dt>
            <dd>{fmtDateTime(j.startedAt)}</dd>
            <dt>Fim</dt>
            <dd>{fmtDateTime(j.finishedAt)}</dd>
            <dt>Duração</dt>
            <dd>{fmtDuration(j.startedAt, j.finishedAt)}</dd>
            <dt>Linhas</dt>
            <dd>{fmtCount(j.rows)}</dd>
            <dt>Rejeições</dt>
            <dd>{fmtCount(j.rejects)}</dd>
          </dl>
          {j.error && (
            <p role="alert" className="field-error">
              Erro: {j.error}
            </p>
          )}
        </>
      )}
      {!(q.error instanceof ApiError && q.error.status === 404) && <JobLog key={jobId} jobId={jobId} />}
    </>
  )
}
