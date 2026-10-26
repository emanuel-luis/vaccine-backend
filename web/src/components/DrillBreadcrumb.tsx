import { Link } from 'react-router'

export interface Crumb {
  label: string
  /** Absent on the current (last) crumb. */
  to?: string
}

/** Brasil > UF > Município. Crumbs outside the caller's scope are passed without `to`. */
export function DrillBreadcrumb({ crumbs }: { crumbs: Crumb[] }) {
  return (
    <nav aria-label="Navegação por região" className="breadcrumb">
      <ol>
        {crumbs.map((c, i) => {
          const last = i === crumbs.length - 1
          return (
            <li key={c.label} aria-current={last ? 'page' : undefined}>
              {c.to && !last ? <Link to={c.to}>{c.label}</Link> : <span>{c.label}</span>}
            </li>
          )
        })}
      </ol>
    </nav>
  )
}
