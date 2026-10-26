import type { HealthUnitRow } from '../api/types'
import { fmtDoses } from '../lib/format'

export function UnitTable({ units }: { units: HealthUnitRow[] }) {
  return (
    <div className="table-wrap">
      <table className="data-table">
        <caption>Unidades de saúde do município</caption>
        <thead>
          <tr>
            <th scope="col">Unidade</th>
            <th scope="col">CNES</th>
            <th scope="col" className="num">
              Doses
            </th>
          </tr>
        </thead>
        <tbody>
          {units.map((u) => (
            <tr key={u.cnesCode}>
              <th scope="row">{u.unitName}</th>
              <td>{u.cnesCode}</td>
              <td className="num">{u.suppressed ? fmtDoses(null) : fmtDoses(u.doses)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
