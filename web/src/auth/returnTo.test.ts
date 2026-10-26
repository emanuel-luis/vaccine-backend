import { afterEach, describe, expect, it, vi } from 'vitest'
import { restoreReturnPath, safeReturnPath } from './returnTo'

describe('safeReturnPath', () => {
  it.each([
    ['/cobertura/BA?ano=2025', '/cobertura/BA?ano=2025'],
    ['/', '/'],
    ['//evil.com', '/painel'],
    ['/\\evil.com', '/painel'],
    ['https://evil.com', '/painel'],
    ['painel', '/painel'],
    [undefined, '/painel'],
    [42, '/painel'],
  ])('%j -> %s', (input, expected) => expect(safeReturnPath(input)).toBe(expected))
})

describe('restoreReturnPath', () => {
  afterEach(() => vi.restoreAllMocks())

  it('replaces the URL and notifies the router', () => {
    const popstate = vi.fn()
    window.addEventListener('popstate', popstate)
    restoreReturnPath({ state: '/cobertura/BA?ano=2025' })
    window.removeEventListener('popstate', popstate)
    expect(window.location.pathname + window.location.search).toBe('/cobertura/BA?ano=2025')
    expect(popstate).toHaveBeenCalledOnce()
  })

  it('defaults to /painel without state', () => {
    restoreReturnPath(undefined)
    expect(window.location.pathname).toBe('/painel')
  })
})
