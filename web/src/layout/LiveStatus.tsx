import { type LiveState, useLive } from '../live/LiveProvider'

const LABEL: Record<LiveState, string> = {
  live: 'Ao vivo',
  reconnecting: 'Reconectando',
  paused: 'Pausado',
}

/** Connection pill plus the rolling "+N doses" ticker (polite live region, refreshed at most every 30 s). */
export function LiveStatus() {
  const { status, recentDoses } = useLive()
  return (
    <>
      <span className={`pill pill-${status}`} role="status" aria-label={`Atualização: ${LABEL[status]}`}>
        <span className="pill-dot" aria-hidden="true" />
        {LABEL[status]}
      </span>
      <span className="live-ticker" aria-live="polite">
        {recentDoses > 0 && (
          <span key={recentDoses} className="live-ticker-value">
            +{recentDoses.toLocaleString('pt-BR')} doses
          </span>
        )}
      </span>
    </>
  )
}
