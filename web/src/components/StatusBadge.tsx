import type { Tone } from '../lib/admin'

/** The text always carries the meaning; the tone only adds color. */
export function StatusBadge({ tone, children }: { tone: Tone; children: string }) {
  return <span className={`badge badge-${tone}`}>{children}</span>
}
