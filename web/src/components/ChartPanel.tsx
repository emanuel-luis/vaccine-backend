import { lazy, Suspense, useId, useState } from 'react'
import type { EChartOption } from '../charts/EChart'
import { StatePanel } from './StatePanel'

const EChart = lazy(() => import('../charts/EChart'))

export interface ChartPanelProps {
  title: string
  /** Text alternative for the chart (aria-label). */
  summary: string
  status: 'loading' | 'empty' | 'error' | 'ready'
  option?: EChartOption
  /** Table alternative: header cells and preformatted rows. */
  columns: string[]
  rows: string[][]
  errorMessage?: string
  onRetry?: () => void
}

export function ChartPanel({ title, summary, status, option, columns, rows, errorMessage, onRetry }: ChartPanelProps) {
  const [table, setTable] = useState(false)
  const headingId = useId()
  return (
    <section className="chart-panel" aria-labelledby={headingId}>
      <div className="panel-head">
        <h2 id={headingId}>{title}</h2>
        {status === 'ready' && (
          <button type="button" className="btn" aria-pressed={table} onClick={() => setTable((t) => !t)}>
            {table ? 'Ver gráfico' : 'Ver tabela'}
          </button>
        )}
      </div>
      {status === 'loading' && <StatePanel state="loading" />}
      {status === 'empty' && <StatePanel state="empty" />}
      {status === 'error' && <StatePanel state="error" message={errorMessage} onRetry={onRetry} />}
      {status === 'ready' && !table && option && (
        <Suspense fallback={<StatePanel state="loading" />}>
          <EChart option={option} label={summary} />
        </Suspense>
      )}
      {status === 'ready' && table && (
        <div className="table-wrap">
          <table className="data-table">
            <caption className="sr-only">{summary}</caption>
            <thead>
              <tr>
                {columns.map((c) => (
                  <th key={c} scope="col">
                    {c}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((r, i) => (
                <tr key={i}>
                  {r.map((cell, j) => (j === 0 ? <th key={j} scope="row">{cell}</th> : <td key={j}>{cell}</td>))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
