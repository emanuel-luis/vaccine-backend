import { describe, expect, it } from 'vitest'
import { row } from '../test/mockApi'
import {
  areaStates,
  areaStatus,
  areaText,
  bbox,
  breaksFor,
  CLASS_COLORS,
  classColor,
  classIndex,
  COVERAGE_BREAKS,
  dosesBreaks,
  legendItems,
  fitOptions,
  NODATA_COLOR,
  viewBbox,
} from './scale'

describe('map scale', () => {
  it('classifies coverage with the upper class on a break', () => {
    expect(classIndex(10, COVERAGE_BREAKS)).toBe(0)
    expect(classIndex(50, COVERAGE_BREAKS)).toBe(1)
    expect(classIndex(94.9, COVERAGE_BREAKS)).toBe(3)
    expect(classIndex(95, COVERAGE_BREAKS)).toBe(4)
    expect(classIndex(120, COVERAGE_BREAKS)).toBe(4)
  })

  it('spreads colors over fewer classes and handles a single class', () => {
    expect(classColor(0, 5)).toBe(CLASS_COLORS[0])
    expect(classColor(4, 5)).toBe(CLASS_COLORS[4])
    expect(classColor(0, 2)).toBe(CLASS_COLORS[0])
    expect(classColor(1, 2)).toBe(CLASS_COLORS[4])
    expect(classColor(0, 1)).toBe(CLASS_COLORS[2])
  })

  it('derives status: no row is outside the scope', () => {
    expect(areaStatus(undefined)).toBe('outside')
    expect(areaStatus(row({ suppressed: true, value: null, reason: 'small_cell' }))).toBe('suppressed')
    expect(areaStatus(row({ value: null, reason: 'no_population' }))).toBe('nodata')
    expect(areaStatus(row({}))).toBe('value')
  })

  it('maps rows to feature-state colors and flags suppressed areas for the hatch layer', () => {
    const states = areaStates(
      [
        row({ code: '1', value: 96 }),
        row({ code: '2', value: 10 }),
        row({ code: '3', value: null, suppressed: true, reason: 'small_cell' }),
        row({ code: '4', value: null, reason: 'no_population' }),
      ],
      COVERAGE_BREAKS,
    )
    expect(states['1']).toEqual({ color: CLASS_COLORS[4], suppressed: false })
    expect(states['2']).toEqual({ color: CLASS_COLORS[0], suppressed: false })
    expect(states['3']).toEqual({ color: NODATA_COLOR, suppressed: true })
    expect(states['4']).toEqual({ color: NODATA_COLOR, suppressed: false })
    expect(states['5']).toBeUndefined()
  })

  it('builds doses breaks from visible values only and de-duplicates', () => {
    expect(dosesBreaks([])).toEqual([])
    expect(dosesBreaks([7, 7, 7, 7, 7])).toEqual([7])
    const rows = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map((v, i) => row({ code: String(i), value: v * 100 }))
    rows.push(row({ code: 'x', value: null, suppressed: true }))
    const b = breaksFor('doses', rows)
    expect(b).toHaveLength(4)
    expect(b).toEqual([...b].sort((x, y) => x - y))
  })

  it('legend lists the classes, the suppressed hatch, no data and out of scope', () => {
    const labels = legendItems('coverage', COVERAGE_BREAKS).map((i) => i.label)
    expect(labels).toEqual([
      '< 50%',
      '50% a 70%',
      '70% a 85%',
      '85% a 95%',
      '≥ 95%',
      'Dados insuficientes (< 5)',
      'Sem dados',
      'Fora do seu escopo',
    ])
    expect(legendItems('doses', []).map((i) => i.kind)).toEqual(['suppressed', 'nodata', 'outside'])
  })

  it('formats area text in pt-BR', () => {
    expect(areaText(row({ value: 87.3 }), 'coverage')).toBe('87,3%')
    expect(areaText(row({ value: 12345 }), 'doses')).toBe('12.345')
    expect(areaText(row({ value: null, suppressed: true }), 'coverage')).toBe('< 5 (dados insuficientes)')
  })

  it('computes the bounding box of nested coordinates and skips null geometries', () => {
    const geo = {
      features: [
        { geometry: { coordinates: [[[[-50, -10], [-40, -5], [-45, -20]]]] as never } },
        { geometry: null },
      ],
    }
    expect(bbox(geo)).toEqual([-50, -20, -40, -5])
  })
})

describe('map view', () => {
  const square = (code: string, x: number) => ({
    type: 'Feature' as const,
    properties: { codarea: code },
    geometry: {
      coordinates: [
        [
          [x, 0],
          [x + 1, 0],
          [x + 1, 1],
          [x, 1],
        ],
      ],
    },
  })
  const geo = { features: [square('1', 0), square('2', 10)] }

  it('fits the selected area, or everything without a selection', () => {
    expect(viewBbox(geo, '2')).toEqual([10, 0, 11, 1])
    expect(viewBbox(geo)).toEqual([0, 0, 11, 1])
  })

  it('falls back to everything when the selected code is not in the geometry', () => {
    expect(viewBbox(geo, '999')).toEqual([0, 0, 11, 1])
  })

  it('animates a selection only without reduced motion', () => {
    expect(fitOptions(true, false).animate).toBe(true)
    expect(fitOptions(true, true).animate).toBe(false)
    expect(fitOptions(false, false).animate).toBe(false)
  })
})
