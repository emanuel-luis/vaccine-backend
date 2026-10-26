import { BarChart, LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import * as echarts from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { useEffect, useRef, useState } from 'react'

echarts.use([BarChart, LineChart, GridComponent, TooltipComponent, CanvasRenderer])

export type EChartOption = echarts.EChartsCoreOption

function token(name: string, fallback: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || fallback
}

/** ECharts theme built from the CSS tokens, so light and dark follow styles.css. */
export function themeFromTokens(): Record<string, unknown> {
  const text = token('--text', '#17202e')
  const muted = token('--muted', '#4a5568')
  const border = token('--border', '#b8c1ce')
  const axis = { axisLine: { lineStyle: { color: border } }, axisLabel: { color: muted }, splitLine: { lineStyle: { color: border, opacity: 0.5 } } }
  return {
    color: [token('--primary', '#0b5cad')],
    backgroundColor: 'transparent',
    textStyle: { color: text },
    categoryAxis: axis,
    valueAxis: axis,
    tooltip: { backgroundColor: token('--surface', '#fff'), borderColor: border, textStyle: { color: text } },
  }
}

/** Re-renders when the root data-theme attribute changes. */
function useThemeName(): string {
  const [name, setName] = useState(() => document.documentElement.dataset.theme ?? 'light')
  useEffect(() => {
    const obs = new MutationObserver(() => setName(document.documentElement.dataset.theme ?? 'light'))
    obs.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
    return () => obs.disconnect()
  }, [])
  return name
}

/** Thin ECharts wrapper: theme from tokens, ResizeObserver, dispose on unmount. */
export default function EChart({ option, label, height = 320 }: { option: EChartOption; label: string; height?: number }) {
  const el = useRef<HTMLDivElement>(null)
  const chart = useRef<echarts.ECharts | null>(null)
  const theme = useThemeName()

  useEffect(() => {
    const node = el.current
    if (!node) return
    const name = `vw-${theme}`
    echarts.registerTheme(name, themeFromTokens())
    const instance = echarts.init(node, name, { renderer: 'canvas' })
    chart.current = instance
    const ro = new ResizeObserver(() => instance.resize())
    ro.observe(node)
    return () => {
      ro.disconnect()
      instance.dispose()
      chart.current = null
    }
  }, [theme])

  useEffect(() => {
    chart.current?.setOption(option, { notMerge: true })
  }, [option, theme])

  return <div ref={el} role="img" aria-label={label} style={{ width: '100%', height }} />
}
