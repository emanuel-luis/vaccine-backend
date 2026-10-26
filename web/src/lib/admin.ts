import { ApiError } from '../api/client'
import type { HourDoses, JobKind } from '../api/types'

export type Tone = 'ok' | 'warn' | 'err' | 'idle'

export const JOB_KINDS: { kind: JobKind; button: string; label: string }[] = [
  { kind: 'load-reference', button: 'Recarregar dados de referência', label: 'Dados de referência' },
  { kind: 'dbt-build', button: 'Rodar dbt build', label: 'dbt build' },
  { kind: 'reconcile', button: 'Reconciliar', label: 'Reconciliação' },
]

export const jobKindLabel = (kind: string) => JOB_KINDS.find((k) => k.kind === kind)?.label ?? kind

const JOB_STATUS: Record<string, { label: string; tone: Tone }> = {
  queued: { label: 'Na fila', tone: 'warn' },
  running: { label: 'Em execução', tone: 'warn' },
  succeeded: { label: 'Concluída', tone: 'ok' },
  failed: { label: 'Falhou', tone: 'err' },
}

export const jobStatus = (s: string) => JOB_STATUS[s] ?? { label: s, tone: 'idle' as Tone }

export const isActive = (status: string) => status === 'queued' || status === 'running'

const DBT_STATUS: Record<string, { label: string; tone: Tone }> = {
  pass: { label: 'Passou', tone: 'ok' },
  success: { label: 'Sucesso', tone: 'ok' },
  warn: { label: 'Alerta', tone: 'warn' },
  fail: { label: 'Falhou', tone: 'err' },
  error: { label: 'Erro', tone: 'err' },
  skipped: { label: 'Ignorado', tone: 'idle' },
}

export const dbtStatus = (s: string) => DBT_STATUS[s] ?? { label: s, tone: 'idle' as Tone }
export const DBT_STATUSES = Object.keys(DBT_STATUS)
export const DBT_TYPES = [
  { value: 'test', label: 'Teste' },
  { value: 'model', label: 'Modelo' },
  { value: 'seed', label: 'Seed' },
]

export function adminErrorMessage(error: unknown, what: string): string {
  if (error instanceof ApiError && error.status === 403) return 'Você não tem acesso a estes dados.'
  return `Não foi possível carregar ${what}.`
}

/** diffKey arrives as a JSON string; objects become "chave: valor, ...", anything else is shown as is. */
export function parseDiffKey(raw: string): string {
  try {
    const v: unknown = JSON.parse(raw)
    if (v !== null && typeof v === 'object' && !Array.isArray(v))
      return Object.entries(v).map(([k, x]) => `${k}: ${String(x)}`).join(', ')
    return Array.isArray(v) ? v.map(String).join(', ') : String(v)
  } catch {
    return raw
  }
}

const HOUR_MS = 3_600_000

/** The last `hours` hourly buckets ending at the hour of `nowMs`; hours the API omits count 0. */
export function zeroFillHours(points: HourDoses[], nowMs: number, hours = 24): { hour: number; doses: number }[] {
  const byHour = new Map<number, number>()
  for (const p of points) {
    const h = Math.floor(Date.parse(p.hour) / HOUR_MS) * HOUR_MS
    byHour.set(h, (byHour.get(h) ?? 0) + p.doses)
  }
  const last = Math.floor(nowMs / HOUR_MS) * HOUR_MS
  return Array.from({ length: hours }, (_, i) => {
    const hour = last - (hours - 1 - i) * HOUR_MS
    return { hour, doses: byHour.get(hour) ?? 0 }
  })
}
