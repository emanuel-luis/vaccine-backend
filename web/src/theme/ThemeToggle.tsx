import { useTheme } from './theme'

export function ThemeToggle() {
  const [theme, toggle] = useTheme()
  return (
    <button type="button" className="btn" aria-pressed={theme === 'dark'} onClick={toggle}>
      {theme === 'dark' ? 'Tema escuro' : 'Tema claro'}
    </button>
  )
}
