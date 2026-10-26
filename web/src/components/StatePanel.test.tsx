import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { StatePanel } from './StatePanel'

describe('StatePanel', () => {
  it('announces loading as status', () => {
    render(<StatePanel state="loading" />)
    expect(screen.getByRole('status')).toHaveTextContent('Carregando')
  })

  it('announces empty as status', () => {
    render(<StatePanel state="empty" />)
    expect(screen.getByRole('status')).toHaveTextContent('Nada por aqui')
  })

  it('announces error as alert and retries', async () => {
    const onRetry = vi.fn()
    render(<StatePanel state="error" message="Falhou" onRetry={onRetry} />)
    expect(screen.getByRole('alert')).toHaveTextContent('Falhou')
    await userEvent.click(screen.getByRole('button', { name: 'Tentar novamente' }))
    expect(onRetry).toHaveBeenCalledOnce()
  })

  it('hides retry without a handler', () => {
    render(<StatePanel state="error" />)
    expect(screen.queryByRole('button')).toBeNull()
  })
})
