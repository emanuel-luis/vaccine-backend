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
  let scope = claims.scope
  if (me?.scope.level === 'BR') scope = { level: 'BR' }
  else if (me?.scope.level === 'UF' && me.scope.state) scope = { level: 'UF', state: me.scope.state }
  else if (me?.scope.level === 'MUN' && me.scope.municipalityCode)
    scope = { level: 'MUN', municipalityCode: me.scope.municipalityCode }

  return {
    authenticated: auth.isAuthenticated,
    name: me?.name || me?.username || profile?.name || profile?.preferred_username || '',
    roles: me ? me.roles.filter((r): r is Role => KNOWN_ROLES.includes(r)) : claims.roles,
    scope,
  }
}
