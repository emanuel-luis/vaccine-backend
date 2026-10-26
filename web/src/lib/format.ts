const nf = new Intl.NumberFormat('pt-BR')
const pf = new Intl.NumberFormat('pt-BR', { minimumFractionDigits: 1, maximumFractionDigits: 1 })

export const SUPPRESSED = '< 5'

/** Count for display; null (suppressed or missing) renders as "< 5". */
export function fmtDoses(v: number | null | undefined): string {
  return v == null ? SUPPRESSED : nf.format(v)
}

/** Coverage percent, e.g. "87,3%". */
export function fmtPct(v: number | null | undefined): string {
  return v == null ? SUPPRESSED : `${pf.format(v)}%`
}

export function ageBandLabel(years: number | null | undefined): string {
  if (years == null) return 'Não informada'
  return years === 1 ? '1 ano' : `${years} anos`
}

export function isoDay(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

export function fmtDate(iso: string): string {
  const [y, m, d] = iso.split('-')
  return y && m && d ? `${d}/${m}/${y}` : iso
}
