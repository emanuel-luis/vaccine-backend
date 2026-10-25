import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './app/App'
import { loadConfig } from './config'
import './styles.css'
import { applyTheme, getInitialTheme } from './theme/theme'

applyTheme(getInitialTheme())
const root = createRoot(document.getElementById('root')!)

loadConfig().then(
  (config) =>
    root.render(
      <StrictMode>
        <App config={config} />
      </StrictMode>,
    ),
  () =>
    root.render(
      <main>
        <h1>Painel de Vacinação</h1>
        <p role="alert">Não foi possível carregar a configuração da aplicação.</p>
      </main>,
    ),
)
