import type { Campaign, CampaignRequest, DailyPoint } from '../api/types'

export interface CampaignValues {
  name: string
  vaccineCode: string
  doseLabel: string
  regionScope: string
  startsOn: string
  endsOn: string
  targetDoses: string
}

export type FieldName = keyof CampaignValues
export type FieldErrors = Partial<Record<FieldName, string>>

export const STALE_MESSAGE = 'Esta campanha foi alterada por outra pessoa. Recarregue.'

/** pt-BR messages for the API's 422 codes (and the 403 region_out_of_scope), with the field they belong to. */
export const API_CODES: Record<string, { field: FieldName; message: string }> = {
  invalid_name: { field: 'name', message: 'Informe um nome de 1 a 120 caracteres.' },
  invalid_region_scope: { field: 'regionScope', message: 'Região inválida.' },
  region_out_of_scope: { field: 'regionScope', message: 'Esta região está fora do seu escopo.' },
  invalid_vaccine_code: { field: 'vaccineCode', message: 'Vacina inválida.' },
  invalid_dose_label: { field: 'doseLabel', message: 'Dose inválida.' },
  unknown_vaccine_dose: { field: 'doseLabel', message: 'Esta combinação de vacina e dose não existe.' },
  ends_before_starts: { field: 'endsOn', message: 'O fim não pode ser antes do início.' },
  invalid_target_doses: { field: 'targetDoses', message: 'A meta deve ser um número inteiro maior que zero.' },
}

const DATE = /^\d{4}-\d{2}-\d{2}$/

/** Client-side checks that mirror the API codes, so most mistakes never leave the browser. */
export function validateCampaign(v: CampaignValues): FieldErrors {
  const e: FieldErrors = {}
  const name = v.name.trim()
  if (name.length < 1 || name.length > 120) e.name = API_CODES.invalid_name!.message
  if (!v.vaccineCode) e.vaccineCode = API_CODES.invalid_vaccine_code!.message
  if (!/^(BR|UF:[A-Z]{2}|MUN:\d{7})$/.test(v.regionScope)) e.regionScope = API_CODES.invalid_region_scope!.message
  if (!DATE.test(v.startsOn)) e.startsOn = 'Informe a data de início.'
  if (!DATE.test(v.endsOn)) e.endsOn = 'Informe a data de fim.'
  else if (DATE.test(v.startsOn) && v.endsOn < v.startsOn) e.endsOn = API_CODES.ends_before_starts!.message
  const target = Number(v.targetDoses)
  if (!/^\d+$/.test(v.targetDoses) || !Number.isSafeInteger(target) || target <= 0 || target > 2_147_483_647)
    e.targetDoses = API_CODES.invalid_target_doses!.message
  return e
}

export function toRequest(v: CampaignValues): CampaignRequest {
  return {
    name: v.name.trim(),
    vaccineCode: v.vaccineCode,
    doseLabel: v.doseLabel || null,
    regionScope: v.regionScope,
    startsOn: v.startsOn,
    endsOn: v.endsOn,
    targetDoses: Number(v.targetDoses),
  }
}

export function fromCampaign(c: Campaign): CampaignValues {
  return {
    name: c.name,
    vaccineCode: c.vaccineCode,
    doseLabel: c.doseLabel ?? '',
    regionScope: c.regionScope,
    startsOn: c.startsOn,
    endsOn: c.endsOn,
    targetDoses: String(c.targetDoses),
  }
}

export type CampaignStatus = 'active' | 'scheduled' | 'ended'

export function statusOf(c: Pick<Campaign, 'startsOn' | 'endsOn'>, today: string): CampaignStatus {
  if (today < c.startsOn) return 'scheduled'
  return today > c.endsOn ? 'ended' : 'active'
}

/** Active first, then scheduled, then ended; newest start first inside each group. */
export function sortCampaigns(list: Campaign[], today: string): Campaign[] {
  const rank = { active: 0, scheduled: 1, ended: 2 }
  return [...list].sort(
    (a, b) => rank[statusOf(a, today)] - rank[statusOf(b, today)] || b.startsOn.localeCompare(a.startsOn),
  )
}

/** The API omits days without doses: fill every day from..to with 0. Suppressed points stay null (a gap). */
export function zeroFill(daily: DailyPoint[], from: string, to: string): { date: string; doses: number | null }[] {
  const byDate = new Map(daily.map((p) => [p.date, p]))
  const out: { date: string; doses: number | null }[] = []
  const end = Date.parse(`${to}T00:00:00Z`)
  for (let t = Date.parse(`${from}T00:00:00Z`); t <= end; t += 86_400_000) {
    const date = new Date(t).toISOString().slice(0, 10)
    const p = byDate.get(date)
    out.push({ date, doses: p ? (p.suppressed ? null : (p.doses ?? 0)) : 0 })
  }
  return out
}
