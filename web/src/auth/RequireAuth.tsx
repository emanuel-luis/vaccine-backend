import { useEffect } from 'react'
import { useAuth } from 'react-oidc-context'
import { Outlet } from 'react-router'
import { StatePanel } from '../components/StatePanel'

/** Login is required for every route below this one: redirects to Keycloak when signed out. */
export function RequireAuth() {
  const auth = useAuth()
  const { isLoading, isAuthenticated, activeNavigator, error, signinRedirect } = auth

  useEffect(() => {
    if (!isLoading && !isAuthenticated && !activeNavigator && !error) void signinRedirect()
  }, [isLoading, isAuthenticated, activeNavigator, error, signinRedirect])

  if (error)
    return (
      <StatePanel
        state="error"
        title="Falha na autenticação"
        message="Não foi possível concluir o login."
        onRetry={() => void signinRedirect()}
      />
    )
  if (!isAuthenticated) return <StatePanel state="loading" message="Redirecionando para o login…" />
  return <Outlet />
}
