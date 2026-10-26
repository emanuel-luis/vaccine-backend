import { describe, expect, it } from 'vitest'
import { parseClaims, scopeLabel } from './claims'

function token(payload: unknown): string {
  const b64 = btoa(String.fromCharCode(...new TextEncoder().encode(JSON.stringify(payload))))
  return `h.${b64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')}.s`
}

describe('parseClaims', () => {
  it('reads roles and national scope', () => {
    const c = parseClaims(token({ roles: ['gestor', 'data-admin', 'other'], region_scope: 'BR' }))
    expect(c.roles).toEqual(['gestor', 'data-admin'])
    expect(c.scope).toEqual({ level: 'BR' })
    expect(scopeLabel(c.scope)).toBe('Brasil')
  })

  it('reads UF scope', () => {
    const c = parseClaims(token({ roles: ['gestor'], region_scope: 'UF:BA' }))
    expect(c.scope).toEqual({ level: 'UF', state: 'BA' })
    expect(scopeLabel(c.scope)).toBe('UF: BA')
  })

  it('reads municipality scope', () => {
    const c = parseClaims(token({ roles: ['profissional'], region_scope: 'MUN:2927408' }))
    expect(c.scope).toEqual({ level: 'MUN', municipalityCode: '2927408' })
    expect(scopeLabel(c.scope)).toBe('Município: 2927408')
  })

  it('decodes non-ASCII payloads', () => {
    expect(parseClaims(token({ name: 'João', roles: ['gestor'] })).roles).toEqual(['gestor'])
  })

  it.each([
    ['undefined', undefined],
    ['no payload', 'abc'],
    ['bad base64', 'a.!!!.c'],
    ['not json', 'a.bm90IGpzb24.c'],
    ['null payload', token(null)],
    ['wrong types', token({ roles: 'gestor', region_scope: 42 })],
  ])('returns empty claims for %s', (_name, input) => {
    expect(parseClaims(input)).toEqual({ roles: [], scope: null })
  })

  it('rejects a malformed scope but keeps roles', () => {
    expect(parseClaims(token({ roles: ['gestor'], region_scope: 'UF:bahia' }))).toEqual({
      roles: ['gestor'],
      scope: null,
    })
  })

  it('labels a missing scope', () => {
    expect(scopeLabel(null)).toBe('Sem escopo')
  })
})
