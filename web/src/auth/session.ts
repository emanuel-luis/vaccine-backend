import { useQuery } from '@tanstack/react-query'
import { useMemo } from 'react'
import { useAuth } from 'react-oidc-context'
import { unwrap, useApi } from '../api/client'
import { parseClaims, type Role, type Scope } from './claims'

export interface Session {
  authenticated: boolean
  name: string
  roles: Role[]
  scope: Scope | null
}

const KNOWN_ROLES: readonly string[] = ['profissional', 'gestor', 'data-admin']

/** Identity for the UI: GET /api/v1/me when it answers, token claims until then (or on failure). */
export function useSession(): Session {
  const auth = useAuth()
  const api = useApi()
  const token = auth.user?.access_token
  const claims = useMemo(() => parseClaims(token), [token])

  const me = useQuery({
    queryKey: ['me'],
    queryFn: async () => unwrap(await api.GET('/api/v1/me')).data,
    enabled: auth.isAuthenticated,
  }).data

  const profile = auth.user?.profile
  // /me answers scope null when the token has no region_scope claim: keep the claim-derived one.
  let scope = claims.scope
  const view = me?.scope
  if (view?.level === 'BR') scope = { level: 'BR' }
  else if (view?.level === 'UF' && view.state) scope = { level: 'UF', state: view.state }
  else if (view?.level === 'MUN' && view.municipalityCode != null)
    scope = { level: 'MUN', municipalityCode: String(view.municipalityCode) }

  return {
    authenticated: auth.isAuthenticated,
    name: me?.name || me?.username || profile?.name || profile?.preferred_username || '',
    roles: me ? me.roles.filter((r): r is Role => KNOWN_ROLES.includes(r)) : claims.roles,
    scope,
  }
}
