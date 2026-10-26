import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useState } from 'react'
import { AuthProvider } from 'react-oidc-context'
import { BrowserRouter } from 'react-router'
import { shouldRetry } from '../api/client'
import { restoreReturnPath } from '../auth/returnTo'
import { type AppConfig, ConfigContext } from '../config'
import { AppRoutes } from './routes'

export function App({ config }: { config: AppConfig }) {
  const [queryClient] = useState(
    () => new QueryClient({ defaultOptions: { queries: { staleTime: 30_000, retry: shouldRetry } } }),
  )
  const oidc = {
    authority: config.oidc.authority,
    client_id: config.oidc.clientId,
    redirect_uri: `${window.location.origin}/`,
    post_logout_redirect_uri: window.location.origin,
    scope: 'openid profile',
    automaticSilentRenew: true,
    // Drops code/state from the URL and restores the deep link saved before login.
    onSigninCallback: restoreReturnPath,
  }
  return (
    <ConfigContext.Provider value={config}>
      <AuthProvider {...oidc}>
        <QueryClientProvider client={queryClient}>
          <BrowserRouter>
            <AppRoutes />
          </BrowserRouter>
        </QueryClientProvider>
      </AuthProvider>
    </ConfigContext.Provider>
  )
}
