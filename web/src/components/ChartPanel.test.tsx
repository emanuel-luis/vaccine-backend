import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ChartPanel, type ChartPanelProps } from './ChartPanel'

vi.mock('../charts/EChart', () => ({
  default: ({ label }: { label: string }) => <div role="img" aria-label={label} data-testid="chart" />,
}))

const base: ChartPanelProps = {
  title: 'Doses por vacina',
  summary: 'Doses por vacina em 2026.',
  status: 'ready',
  option: {},
  columns: ['Vacina', 'Doses'],
  rows: [
    ['BCG', '1.200'],
    ['Pentavalente', '< 5'],
  ],
}

describe('ChartPanel', () => {
  it('shows the chart with an aria-label summary', async () => {
    render(<ChartPanel {...base} />)
    expect(await screen.findByRole('img', { name: 'Doses por vacina em 2026.' })).toBeInTheDocument()
  })

  it('toggles an accessible table with suppressed cells', async () => {
    const user = userEvent.setup()
    render(<ChartPanel {...base} />)
    await screen.findByTestId('chart')
    await user.click(screen.getByRole('button', { name: 'Ver tabela' }))
    expect(screen.queryByTestId('chart')).toBeNull()
    const table = screen.getByRole('table')
    expect(table).toHaveAccessibleName('Doses por vacina em 2026.')
    expect(screen.getByRole('columnheader', { name: 'Vacina' })).toBeInTheDocument()
    expect(screen.getByRole('rowheader', { name: 'Pentavalente' })).toBeInTheDocument()
    expect(screen.getByRole('cell', { name: '< 5' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Ver gráfico' }))
    expect(await screen.findByTestId('chart')).toBeInTheDocument()
  })

  it('renders loading, empty and error states without a toggle', async () => {
    const retry = vi.fn()
    const { rerender } = render(<ChartPanel {...base} status="loading" />)
    expect(screen.getByRole('status')).toHaveTextContent('Carregando')
    rerender(<ChartPanel {...base} status="empty" />)
    expect(screen.getByRole('status')).toHaveTextContent('Nada por aqui')
    rerender(<ChartPanel {...base} status="error" errorMessage="Falhou." onRetry={retry} />)
    expect(screen.getByRole('alert')).toHaveTextContent('Falhou.')
    expect(screen.queryByRole('button', { name: 'Ver tabela' })).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: 'Tentar novamente' }))
    expect(retry).toHaveBeenCalledOnce()
  })
})
