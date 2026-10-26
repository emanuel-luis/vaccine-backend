import type { ReactNode } from 'react'
import { Navigate, Outlet } from 'react-router'
import type { Role } from './claims'
import { useSession } from './session'

/** Renders children (or the nested routes) when the user has any of `roles`; else /acesso-negado. */
export function RequireRole({ roles, children }: { roles: Role[]; children?: ReactNode }) {
  const session = useSession()
  if (!session.roles.some((r) => roles.includes(r))) return <Navigate to="/acesso-negado" replace />
  return children ?? <Outlet />
}
