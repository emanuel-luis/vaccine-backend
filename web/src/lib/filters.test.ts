import { describe, expect, it } from 'vitest'
import { VACCINES } from '../test/mockApi'
import { ageBandOf, effectiveVaccineDose, patchParams, readFilters, resolvePeriod } from './filters'

const now = new Date(2026, 9, 25)

describe('filters', () => {
  it('patches params, deleting empty values and keeping unrelated keys', () => {
    const next = patchParams(new URLSearchParams('x=1&faixa=2'), { faixa: '', vacina: 'BCG' })
    expect(next.toString()).toBe('x=1&vacina=BCG')
    expect(readFilters(next)).toEqual({ vacina: 'BCG' })
  })

  it('resolves the period: explicit range, chosen year, or year to date', () => {
    expect(resolvePeriod({ de: '2026-01-05', ate: '2026-02-01' }, now)).toEqual({ from: '2026-01-05', to: '2026-02-01' })
    expect(resolvePeriod({ ano: '2024' }, now)).toEqual({ from: '2024-01-01', to: '2024-12-31' })
    expect(resolvePeriod({}, now)).toEqual({ from: '2026-01-01', to: '2026-10-25' })
    expect(resolvePeriod({ de: '2026-01-05' }, now)).toEqual({ from: '2026-01-01', to: '2026-10-25' })
  })

  it('accepts only age bands 0 to 6', () => {
    expect(ageBandOf({ faixa: '0' })).toBe(0)
    expect(ageBandOf({ faixa: '6' })).toBe(6)
    expect(ageBandOf({ faixa: '7' })).toBeUndefined()
    expect(ageBandOf({ faixa: 'x' })).toBeUndefined()
    expect(ageBandOf({})).toBeUndefined()
  })

  it('defaults coverage to the first indicator dose and ignores non-indicator doses', () => {
    expect(effectiveVaccineDose({}, VACCINES, true)).toEqual({ vaccine: 'BCG', dose: 'DU' })
    expect(effectiveVaccineDose({ vacina: 'PENTA' }, VACCINES, true)).toEqual({ vaccine: 'PENTA', dose: 'D1' })
    expect(effectiveVaccineDose({ vacina: 'PENTA', dose: 'R1' }, VACCINES, true)).toEqual({ vaccine: 'BCG', dose: 'DU' })
    expect(effectiveVaccineDose({}, [], true)).toEqual({})
    expect(effectiveVaccineDose({ vacina: 'PENTA', dose: 'R1' }, VACCINES, false)).toEqual({ vaccine: 'PENTA', dose: 'R1' })
  })
})
