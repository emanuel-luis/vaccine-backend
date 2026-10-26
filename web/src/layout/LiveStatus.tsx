export type LiveState = 'live' | 'reconnecting' | 'paused'

const LABEL: Record<LiveState, string> = {
  live: 'Ao vivo',
  reconnecting: 'Reconectando',
  paused: 'Pausado',
}

// Static for now: the SSE LiveProvider arrives with the dashboards.
export function LiveStatus({ state = 'paused' }: { state?: LiveState }) {
  return (
    <span className={`pill pill-${state}`} role="status" aria-label={`Atualização: ${LABEL[state]}`}>
      <span className="pill-dot" aria-hidden="true" />
      {LABEL[state]}
    </span>
  )
}
