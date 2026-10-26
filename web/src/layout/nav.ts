import type { Role } from '../auth/claims'

export interface NavItem {
  to: string
  label: string
}

export interface NavGroup {
  label: string
  items: NavItem[]
}

const MAIN: NavItem[] = [
  { to: '/painel', label: 'Painel' },
  { to: '/cobertura', label: 'Cobertura' },
  { to: '/alertas', label: 'Alertas' },
  { to: '/campanhas', label: 'Campanhas' },
]

const DATA: NavGroup = {
  label: 'Dados',
  items: [
    { to: '/dados/fontes', label: 'Fontes' },
    { to: '/dados/execucoes', label: 'Execuções' },
    { to: '/dados/testes', label: 'Testes de qualidade' },
    { to: '/dados/reconciliacao', label: 'Reconciliação' },
  ],
}

const ABOUT: NavItem = { to: '/sobre', label: 'Sobre' }

/** Nav entries for a role set; signed-out visitors only get Sobre. */
export function navFor(authenticated: boolean, roles: Role[]): { items: NavItem[]; groups: NavGroup[]; about: NavItem } {
  if (!authenticated) return { items: [], groups: [], about: ABOUT }
  return { items: MAIN, groups: roles.includes('data-admin') ? [DATA] : [], about: ABOUT }
}
