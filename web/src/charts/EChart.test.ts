import { describe, expect, it } from 'vitest'
import { themeFromTokens } from './EChart'

describe('chart theme', () => {
  it('formats axis labels and tooltips with pt-BR separators', () => {
    const theme = themeFromTokens() as {
      valueAxis: { axisLabel: { formatter: (v: number) => string } }
      tooltip: { valueFormatter: (v: number) => string }
    }
    expect(theme.valueAxis.axisLabel.formatter(10000)).toBe('10.000')
    expect(theme.tooltip.valueFormatter(104094)).toBe('104.094')
  })
})
