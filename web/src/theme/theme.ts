import { useEffect, useState } from 'react'

export type Theme = 'light' | 'dark'

const KEY = 'theme'

function stored(): Theme | null {
  try {
    const v = localStorage.getItem(KEY)
    return v === 'light' || v === 'dark' ? v : null
  } catch {
    return null
  }
}

export function getInitialTheme(): Theme {
  return (
    stored() ?? (window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light')
  )
}

export function applyTheme(theme: Theme): void {
  document.documentElement.dataset.theme = theme
}

/** Current theme and a toggle that persists the explicit choice (never throws without storage). */
export function useTheme(): [Theme, () => void] {
  const [theme, setTheme] = useState<Theme>(getInitialTheme)
  useEffect(() => applyTheme(theme), [theme])
  const toggle = () => {
    const next: Theme = theme === 'dark' ? 'light' : 'dark'
    setTheme(next)
    try {
      localStorage.setItem(KEY, next)
    } catch {
      /* storage blocked: the choice lasts for this page view only */
    }
  }
  return [theme, toggle]
}
