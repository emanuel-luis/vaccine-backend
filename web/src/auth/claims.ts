export type Role = 'profissional' | 'gestor' | 'data-admin'

export type Scope =
  | { level: 'BR' }
  | { level: 'UF'; state: string }
  | { level: 'MUN'; municipalityCode: string }

export interface Claims {
  roles: Role[]
  scope: Scope | null
}

const ROLES: readonly string[] = ['profissional', 'gestor', 'data-admin']

export function parseScope(value: unknown): Scope | null {
  if (typeof value !== 'string') return null
  if (value === 'BR') return { level: 'BR' }
  const uf = /^UF:([A-Z]{2})$/.exec(value)
  if (uf?.[1]) return { level: 'UF', state: uf[1] }
  const mun = /^MUN:(\d{7})$/.exec(value)
  if (mun?.[1]) return { level: 'MUN', municipalityCode: mun[1] }
  return null
}

/** Reads roles and region scope from a JWT payload. No signature check: the API is the authority. */
export function parseClaims(token: string | undefined): Claims {
  const empty: Claims = { roles: [], scope: null }
  const part = token?.split('.')[1]
  if (!part) return empty
  try {
    const b64 = part.replace(/-/g, '+').replace(/_/g, '/')
    const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0))
    const payload: unknown = JSON.parse(new TextDecoder().decode(bytes))
    if (typeof payload !== 'object' || payload === null) return empty
    const { roles, region_scope } = payload as Record<string, unknown>
    return {
      roles: Array.isArray(roles) ? roles.filter((r): r is Role => ROLES.includes(r as string)) : [],
      scope: parseScope(region_scope),
    }
  } catch {
    return empty
  }
}

export function scopeLabel(scope: Scope | null): string {
  if (!scope) return 'Sem escopo'
  if (scope.level === 'BR') return 'Brasil'
  if (scope.level === 'UF') return `UF: ${scope.state}`
  return `Município: ${scope.municipalityCode}`
}
