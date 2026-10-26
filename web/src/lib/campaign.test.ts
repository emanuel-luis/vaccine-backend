import { describe, expect, it } from 'vitest'
import { sortCampaigns, validateCampaign, zeroFill } from './campaign'

const ok = {
  name: 'Multivacinação',
  vaccineCode: 'BCG',
  doseLabel: '',
  regionScope: 'UF:BA',
  startsOn: '2026-10-01',
  endsOn: '2026-10-31',
  targetDoses: '500',
}

describe('zeroFill', () => {
  it('fills days the API omitted with 0 and keeps suppressed points as gaps', () => {
    const out = zeroFill(
      [
        { date: '2026-10-01', doses: 7, suppressed: false },
        { date: '2026-10-03', doses: null, suppressed: true },
      ],
      '2026-10-01',
      '2026-10-04',
    )
    expect(out).toEqual([
      { date: '2026-10-01', doses: 7 },
      { date: '2026-10-02', doses: 0 },
      { date: '2026-10-03', doses: null },
      { date: '2026-10-04', doses: 0 },
    ])
  })

  it('returns nothing when the range is empty (campaign not started)', () => {
    expect(zeroFill([], '2026-11-01', '2026-10-26')).toEqual([])
  })
})

describe('validateCampaign', () => {
  it('accepts a valid campaign', () => expect(validateCampaign(ok)).toEqual({}))

  it('mirrors the API rules', () => {
    const e = validateCampaign({
      ...ok,
      name: ' ',
      vaccineCode: '',
      regionScope: 'SP',
      endsOn: '2026-09-30',
      targetDoses: '0',
    })
    expect(Object.keys(e).sort()).toEqual(['endsOn', 'name', 'regionScope', 'targetDoses', 'vaccineCode'])
    expect(e.endsOn).toBe('O fim não pode ser antes do início.')
  })

  it('rejects names over 120 characters and non-integer targets', () => {
    expect(validateCampaign({ ...ok, name: 'x'.repeat(121) }).name).toBeDefined()
    expect(validateCampaign({ ...ok, targetDoses: '1,5' }).targetDoses).toBeDefined()
  })
})

describe('sortCampaigns', () => {
  it('puts active campaigns first', () => {
    const c = (id: string, startsOn: string, endsOn: string) => ({ campaignId: id, startsOn, endsOn }) as never
    const out = sortCampaigns([c('old', '2025-01-01', '2025-02-01'), c('now', '2026-10-01', '2026-11-01')], '2026-10-26')
    expect(out.map((x: { campaignId: string }) => x.campaignId)).toEqual(['now', 'old'])
  })
})
