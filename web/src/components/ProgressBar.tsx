import { fmtPct } from '../lib/format'

/** Progress toward a target; pct null (suppressed or not computed) shows "< 5" and an empty bar. */
export function ProgressBar({ pct, label }: { pct: number | null | undefined; label: string }) {
  const v = pct == null ? 0 : Math.max(0, Math.min(100, pct))
  return (
    <span className="cov">
      <progress className="progress" max={100} value={v} aria-label={label} />
      <span className="cov-num">{fmtPct(pct)}</span>
    </span>
  )
}
