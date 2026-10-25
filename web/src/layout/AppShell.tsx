import { useState } from 'react'
import { useAuth } from 'react-oidc-context'
import { NavLink, Outlet } from 'react-router'
import { scopeLabel } from '../auth/claims'
import { useSession } from '../auth/session'
import { ThemeToggle } from '../theme/ThemeToggle'
import { LiveStatus } from './LiveStatus'
import { navFor } from './nav'

export function AppShell() {
  const auth = useAuth()
  const session = useSession()
  const [open, setOpen] = useState(false)
  const nav = navFor(session.authenticated, session.roles)
  const close = () => setOpen(false)
  const link = (i: { to: string; label: string }) => (
    <li key={i.to}>
      <NavLink to={i.to} onClick={close}>
        {i.label}
      </NavLink>
    </li>
  )

  return (
    <>
      <a className="skip-link" href="#conteudo">
        Ir para o conteúdo
      </a>
      <header className="app-header">
        <p className="app-name">Painel de Vacinação</p>
        <button
          type="button"
          className="btn menu-button"
          aria-expanded={open}
          aria-controls="menu-principal"
          onClick={() => setOpen((o) => !o)}
        >
          Menu
        </button>
        <div className="header-tools">
          {session.authenticated ? (
            <>
              {session.name && <span className="user-name">{session.name}</span>}
              <span className="badge">{scopeLabel(session.scope)}</span>
              <LiveStatus />
              <ThemeToggle />
              <button type="button" className="btn" onClick={() => void auth.signoutRedirect()}>
                Sair
              </button>
            </>
          ) : (
            <>
              <ThemeToggle />
              <button type="button" className="btn" onClick={() => void auth.signinRedirect()}>
                Entrar
              </button>
            </>
          )}
        </div>
        <nav id="menu-principal" aria-label="Principal" className={open ? 'main-nav open' : 'main-nav'}>
          <ul>
            {nav.items.map(link)}
            {nav.groups.map((g) => (
              <li key={g.label} className="nav-group">
                <span className="nav-group-label">{g.label}</span>
                <ul>{g.items.map(link)}</ul>
              </li>
            ))}
            {link(nav.about)}
          </ul>
        </nav>
      </header>
      <main id="conteudo" tabIndex={-1}>
        <Outlet />
      </main>
    </>
  )
}
