const dt = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeStyle: 'medium' })
const hm = new Intl.DateTimeFormat('pt-BR', { hour: '2-digit', minute: '2-digit' })
const nf = new Intl.NumberFormat('pt-BR')
const sf = new Intl.NumberFormat('pt-BR', { minimumFractionDigits: 1, maximumFractionDigits: 1 })

const DASH = '—'

export const fmtDateTime = (iso: string | null | undefined) => (iso ? dt.format(new Date(iso)) : DASH)
export const fmtHour = (ms: number) => hm.format(new Date(ms))
export const fmtCount = (v: number | null | undefined) => (v == null ? DASH : nf.format(v))

/** "1 min 05 s" or "12,3 s". */
export function fmtSeconds(s: number | null | undefined): string {
  if (s == null) return DASH
  if (s < 60) return `${sf.format(s)} s`
  const total = Math.round(s)
  return `${Math.floor(total / 60)} min ${String(total % 60).padStart(2, '0')} s`
}

export function fmtDuration(from: string | null | undefined, to: string | null | undefined): string {
  if (!from || !to) return DASH
  return fmtSeconds((Date.parse(to) - Date.parse(from)) / 1000)
}
