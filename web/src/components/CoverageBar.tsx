import { fmtPct } from '../lib/format'

/** Coverage bar with a goal marker; the numbers beside it carry the meaning, the bar is decoration. */
export function CoverageBar({ pct, goal }: { pct: number; goal?: number }) {
  const clamp = (v: number) => `${Math.max(0, Math.min(100, v))}%`
  return (
    <span className="cov">
      <span className="cov-track" aria-hidden="true">
        <span className="cov-fill" style={{ width: clamp(pct) }} />
        {goal !== undefined && <span className="cov-goal" style={{ left: clamp(goal) }} />}
      </span>
      <span className="cov-num">{fmtPct(pct)}</span>
    </span>
  )
}
