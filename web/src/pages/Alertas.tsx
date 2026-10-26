import { Link, useSearchParams } from 'react-router'
import { ApiError } from '../api/client'
import { useAlerts, useVaccines } from '../api/queries'
import type { AlertsQuery, LowCoverage } from '../api/types'
import { useSession } from '../auth/session'
import { CoverageBar } from '../components/CoverageBar'
import { StatePanel } from '../components/StatePanel'
import { fmtDoses, fmtPct } from '../lib/format'
import { UF_BY_CODE, ufOfMunicipality } from '../lib/regions'

const year = new Date().getFullYear()

function errorMessage(error: unknown): string {
  if (error instanceof ApiError && error.status === 403) return 'Você não tem acesso a estes dados.'
  if (error instanceof ApiError && error.status === 503)
    return 'Os dados ainda não estão disponíveis. Tente novamente em instantes.'
  return 'Não foi possível carregar os alertas.'
}

/** Gap descending, then name: the API already sorts, this keeps the table right if it ever does not. */
export function sortByGap(rows: LowCoverage[]): LowCoverage[] {
  return [...rows].sort((a, b) => b.gapPp - a.gapPp || a.name.localeCompare(b.name, 'pt-BR'))
}

export function Alertas() {
  const [params, setParams] = useSearchParams()
  const { scope } = useSession()
  const vaccines = useVaccines().data ?? []

  const level: 'state' | 'municipality' = params.get('nivel') === 'municipality' ? 'municipality' : 'state'
  const ano = /^\d{4}$/.test(params.get('ano') ?? '') ? Number(params.get('ano')) : year
  const scopedUf =
    scope?.level === 'UF' ? scope.state : scope?.level === 'MUN' ? ufOfMunicipality(scope.municipalityCode) : undefined
  const ufs = scopedUf ? [scopedUf] : Object.values(UF_BY_CODE).sort()
  const uf = params.get('uf') ?? scopedUf ?? ''
  const [vaccine, dose] = (params.get('vacina') ?? '').split('|')
  const indicators = vaccines.filter((v) => v.isCoverageIndicator)

  const set = (patch: Record<string, string>) =>
    setParams((p) => {
      const n = new URLSearchParams(p)
      for (const [k, v] of Object.entries(patch)) if (v) n.set(k, v)
      else n.delete(k)
      return n
    })

  const needsUf = level === 'municipality' && !uf
  const query: AlertsQuery = {
    level,
    year: ano,
    state: level === 'municipality' ? uf : undefined,
    vaccine: vaccine || undefined,
    dose: vaccine && dose ? dose : undefined,
  }
  const alerts = useAlerts(query, !needsUf)
  const rows = sortByGap(alerts.data ?? [])
  const nameOf = (code: string) => vaccines.find((v) => v.vaccineCode === code)?.vaccineName ?? code

  const link = (r: LowCoverage) => {
    const q = new URLSearchParams({ ano: String(ano), vacina: r.vaccineCode, dose: r.doseLabel })
    const path =
      r.level === 'state' ? `/cobertura/${r.abbr ?? UF_BY_CODE[r.code] ?? ''}` : `/cobertura/${uf || ufOfMunicipality(r.code) || ''}/${r.code}`
    return `${path}?${q}`
  }

  return (
    <>
      <h1>Alertas de baixa cobertura</h1>
      <form className="filter-form" aria-label="Filtros dos alertas" onSubmit={(e) => e.preventDefault()}>
        <label>
          Ano
          <select value={String(ano)} onChange={(e) => set({ ano: e.target.value })}>
            {Array.from({ length: 6 }, (_, i) => year - i).map((y) => (
              <option key={y} value={y}>
                {y}
              </option>
            ))}
          </select>
        </label>
        <label>
          Nível
          <select value={level} onChange={(e) => set({ nivel: e.target.value })}>
            <option value="state">Estado</option>
            <option value="municipality">Município</option>
          </select>
        </label>
        <label>
          UF
          <select value={uf} disabled={level === 'state'} onChange={(e) => set({ uf: e.target.value })}>
            <option value="">Escolha</option>
            {ufs.map((u) => (
              <option key={u} value={u}>
                {u}
              </option>
            ))}
          </select>
        </label>
        <label>
          Vacina e dose
          <select value={vaccine ? `${vaccine}|${dose ?? ''}` : ''} onChange={(e) => set({ vacina: e.target.value })}>
            <option value="">Todas</option>
            {indicators.map((v) => (
              <option key={`${v.vaccineCode}|${v.doseLabel}`} value={`${v.vaccineCode}|${v.doseLabel}`}>
                {v.vaccineName} {v.doseLabel}
              </option>
            ))}
          </select>
        </label>
      </form>
      {needsUf && <StatePanel state="empty" title="Escolha uma UF" message="Para ver municípios, escolha a UF." />}
      {!needsUf && alerts.isPending && <StatePanel state="loading" />}
      {alerts.isError && <StatePanel state="error" message={errorMessage(alerts.error)} onRetry={() => void alerts.refetch()} />}
      {alerts.data && rows.length === 0 && (
        <StatePanel state="empty" title="Nenhum alerta" message="Nenhuma área está abaixo da meta para os filtros escolhidos." />
      )}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table className="data-table">
            <caption>Áreas abaixo da meta de cobertura em {ano}, da maior para a menor lacuna.</caption>
            <thead>
              <tr>
                <th scope="col">Área</th>
                <th scope="col">Vacina</th>
                <th scope="col">Cobertura</th>
                <th scope="col" className="num">Meta</th>
                <th scope="col" className="num">Lacuna</th>
                <th scope="col" className="num">Doses / alvo</th>
                <th scope="col">Mapa</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={`${r.code}-${r.vaccineCode}-${r.doseLabel}`}>
                  <th scope="row">{r.abbr ? `${r.name} (${r.abbr})` : r.name}</th>
                  <td>
                    {nameOf(r.vaccineCode)} {r.doseLabel}
                  </td>
                  <td>
                    <CoverageBar pct={r.coveragePct} goal={r.goalPct} />
                  </td>
                  <td className="num">{fmtPct(r.goalPct)}</td>
                  <td className="num">{fmtPct(r.gapPp).replace('%', ' p.p.')}</td>
                  <td className="num">
                    {fmtDoses(r.numerator)} / {fmtDoses(r.denominator)}
                  </td>
                  <td>
                    <Link to={link(r)} aria-label={`Ver ${r.name} na cobertura`}>
                      Ver
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}
