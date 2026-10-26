import '@testing-library/jest-dom/vitest'
import { cleanup, configure } from '@testing-library/react'
import { afterEach } from 'vitest'

// Lazy chunks (map, charts, pages) can take longer than the 1 s default to resolve when every test
// file runs in parallel; a longer wait for findBy*/waitFor removes that flakiness without slowing
// passing tests.
configure({ asyncUtilTimeout: 5000 })

afterEach(() => {
  cleanup()
  localStorage.clear()
})
