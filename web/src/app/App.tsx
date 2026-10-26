import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useState } from 'react'
import { AuthProvider } from 'react-oidc-context'
import { BrowserRouter } from 'react-router'
import { shouldRetry } from '../api/client'
import { useSession } from '../auth/session'
import { restoreReturnPath } from '../auth/returnTo'
import { type AppConfig, ConfigContext } from '../config'
import { LiveProvider } from '../live/LiveProvider'
import { AppRoutes } from './routes'

function Live({ children }: { children: React.ReactNode }) {
  return <LiveProvider scope={useSession().scope}>{children}</LiveProvider>
}

export function App({ config }: { config: AppConfig }) {
  const [queryClient] = useState(
    () => new QueryClient({ defaultOptions: { queries: { staleTime: 30_000, retry: shouldRetry } } }),
  )
  const oidc = {
    authority: config.oidc.authority,
    client_id: config.oidc.clientId,
    redirect_uri: `${window.location.origin}/`,
    post_logout_redirect_uri: window.location.origin,
    // The realm import has no built-in `profile` scope (Keycloak rejects it as invalid_scope);
    // the claims the SPA reads come from the default `vaccine-api` scope.
    scope: 'openid',
    automaticSilentRenew: true,
    // Drops code/state from the URL and restores the deep link saved before login.
    onSigninCallback: restoreReturnPath,
  }
  return (
    <ConfigContext.Provider value={config}>
      <AuthProvider {...oidc}>
        <QueryClientProvider client={queryClient}>
          <Live>
            <BrowserRouter>
              <AppRoutes />
            </BrowserRouter>
          </Live>
        </QueryClientProvider>
      </AuthProvider>
    </ConfigContext.Provider>
  )
}
