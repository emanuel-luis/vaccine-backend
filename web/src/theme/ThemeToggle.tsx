import { useTheme } from './theme'

export function ThemeToggle() {
  const [theme, toggle] = useTheme()
  // Fixed name + aria-pressed (WAI-ARIA toggle button): the state is announced, the label never changes.
  return (
    <button type="button" className="btn" aria-pressed={theme === 'dark'} onClick={toggle}>
      Tema escuro
    </button>
  )
}
