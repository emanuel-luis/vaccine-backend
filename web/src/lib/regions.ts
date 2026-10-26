import type { Scope } from '../auth/claims'

/** IBGE UF code (first 2 digits of any area code) to abbreviation. Static reference data. */
export const UF_BY_CODE: Record<string, string> = {
  '11': 'RO', '12': 'AC', '13': 'AM', '14': 'RR', '15': 'PA', '16': 'AP', '17': 'TO',
  '21': 'MA', '22': 'PI', '23': 'CE', '24': 'RN', '25': 'PB', '26': 'PE', '27': 'AL', '28': 'SE', '29': 'BA',
  '31': 'MG', '32': 'ES', '33': 'RJ', '35': 'SP',
  '41': 'PR', '42': 'SC', '43': 'RS',
  '50': 'MS', '51': 'MT', '52': 'GO', '53': 'DF',
}

export function ufOfMunicipality(code: string): string | undefined {
  return UF_BY_CODE[code.slice(0, 2)]
}

/** Region query parameter for a drill position: BR, UF:XX or MUN:1234567. */
export function regionParam(uf?: string, municipio?: string): string {
  if (municipio) return `MUN:${municipio}`
  if (uf) return `UF:${uf}`
  return 'BR'
}

/** Is the area (state abbreviation or municipality code) inside the caller's scope? */
export function inScope(scope: Scope | null, area: { uf?: string; municipio?: string }): boolean {
  if (!scope) return false
  if (scope.level === 'BR') return true
  if (scope.level === 'UF') return area.uf === scope.state
  return area.municipio === scope.municipalityCode
}

/** Landing path for a scope on /cobertura. */
export function coverageHome(scope: Scope | null): string {
  if (scope?.level === 'UF') return `/cobertura/${scope.state}`
  if (scope?.level === 'MUN') {
    const uf = ufOfMunicipality(scope.municipalityCode)
    if (uf) return `/cobertura/${uf}/${scope.municipalityCode}`
  }
  return '/cobertura'
}
