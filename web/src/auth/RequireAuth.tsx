import { useEffect } from 'react'
import { useAuth } from 'react-oidc-context'
import { Outlet, useLocation } from 'react-router'
import { StatePanel } from '../components/StatePanel'

/** Login is required for every route below this one: redirects to Keycloak when signed out. */
export function RequireAuth() {
  const auth = useAuth()
  const { isLoading, isAuthenticated, activeNavigator, error, signinRedirect } = auth
  const { pathname, search } = useLocation()
  // Carried back as user.state after login so deep links survive.
  const returnTo = pathname + search

  useEffect(() => {
    if (!isLoading && !isAuthenticated && !activeNavigator && !error) void signinRedirect({ state: returnTo })
  }, [isLoading, isAuthenticated, activeNavigator, error, signinRedirect, returnTo])

  if (error)
    return (
      <StatePanel
        state="error"
        title="Falha na autenticação"
        message="Não foi possível concluir o login."
        onRetry={() => void signinRedirect({ state: returnTo })}
      />
    )
  if (!isAuthenticated) return <StatePanel state="loading" message="Redirecionando para o login…" />
  return <Outlet />
}
