import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import { DrillBreadcrumb } from './DrillBreadcrumb'

const show = (crumbs: Parameters<typeof DrillBreadcrumb>[0]['crumbs']) =>
  render(
    <MemoryRouter>
      <DrillBreadcrumb crumbs={crumbs} />
    </MemoryRouter>,
  )

describe('DrillBreadcrumb', () => {
  it('links the parents and marks the last crumb as the current page', () => {
    show([{ label: 'Brasil', to: '/cobertura' }, { label: 'BA', to: '/cobertura/BA' }, { label: 'Salvador' }])
    const nav = within(screen.getByRole('navigation', { name: 'Navegação por região' }))
    expect(nav.getByRole('link', { name: 'Brasil' })).toHaveAttribute('href', '/cobertura')
    expect(nav.getByRole('link', { name: 'BA' })).toHaveAttribute('href', '/cobertura/BA')
    expect(nav.queryByRole('link', { name: 'Salvador' })).toBeNull()
    expect(nav.getByText('Salvador').closest('li')).toHaveAttribute('aria-current', 'page')
  })

  it('renders crumbs outside the scope as plain text', () => {
    show([{ label: 'Brasil' }, { label: 'BA' }])
    expect(screen.queryAllByRole('link')).toHaveLength(0)
    expect(screen.getByText('Brasil')).toBeInTheDocument()
  })
})
