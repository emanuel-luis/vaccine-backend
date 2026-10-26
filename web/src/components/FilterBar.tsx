import { useId, useState } from 'react'
import { useRegions, useVaccines } from '../api/queries'
import { useSession } from '../auth/session'
import { effectiveVaccineDose, useFilters } from '../lib/filters'
import { ageBandLabel } from '../lib/format'

export interface FilterBarProps {
  /** Coverage mode: vaccine and dose are limited to indicator doses and always set. */
  indicatorOnly?: boolean
  /** The coverage screen takes the region from the route instead. */
  hideRegion?: boolean
}

const BANDS = [0, 1, 2, 3, 4, 5, 6]

function yearOptions(): number[] {
  const y = new Date().getFullYear()
  return Array.from({ length: 6 }, (_, i) => y - i)
}

/** Filters live in the URL search params (de, ate, ano, regiao, vacina, dose, faixa). */
export function FilterBar({ indicatorOnly = false, hideRegion = false }: FilterBarProps) {
  const [filters, update] = useFilters()
  const [open, setOpen] = useState(false)
  const formId = useId()
  const { scope } = useSession()
  const vaccines = useVaccines().data ?? []
  const regions = useRegions(scope?.level === 'UF' ? scope.state : undefined, scope?.level === 'BR' || scope?.level === 'UF')

  const pool = indicatorOnly ? vaccines.filter((v) => v.isCoverageIndicator) : vaccines
  const vaccineOptions = [...new Map(pool.map((v) => [v.vaccineCode, v.vaccineName])).entries()]
  const eff = effectiveVaccineDose(filters, vaccines, indicatorOnly)
  const doseOptions = pool.filter((v) => v.vaccineCode === eff.vaccine).map((v) => v.doseLabel)

  const regionOptions: { value: string; label: string }[] = []
  if (scope?.level === 'BR') regionOptions.push({ value: '', label: 'Brasil' })
  if (scope?.level === 'UF') regionOptions.push({ value: '', label: `UF ${scope.state}` })
  if (scope?.level === 'MUN')
    regionOptions.push({ value: '', label: `Município ${scope.municipalityCode}` })
  for (const r of regions.data ?? [])
    regionOptions.push({ value: r.level === 'state' ? `UF:${r.abbr ?? r.code}` : `MUN:${r.code}`, label: r.name })
  if (filters.regiao && !regionOptions.some((o) => o.value === filters.regiao))
    regionOptions.push({ value: filters.regiao, label: filters.regiao })

  return (
    <div className="filter-bar">
      <button
        type="button"
        className="btn filter-toggle"
        aria-expanded={open}
        aria-controls={formId}
        onClick={() => setOpen((o) => !o)}
      >
        Filtros
      </button>
      <form
        id={formId}
        className={open ? 'filter-form open' : 'filter-form'}
        aria-label="Filtros"
        onSubmit={(e) => e.preventDefault()}
      >
        <label>
          De
          <input type="date" value={filters.de ?? ''} max={filters.ate} onChange={(e) => update({ de: e.target.value })} />
        </label>
        <label>
          Até
          <input type="date" value={filters.ate ?? ''} min={filters.de} onChange={(e) => update({ ate: e.target.value })} />
        </label>
        <label>
          Ano
          <select value={filters.ano ?? ''} onChange={(e) => update({ ano: e.target.value })}>
            <option value="">Atual</option>
            {yearOptions().map((y) => (
              <option key={y} value={y}>
                {y}
              </option>
            ))}
          </select>
        </label>
        {!hideRegion && (
          <label>
            Região
            <select
              value={filters.regiao ?? ''}
              disabled={scope?.level === 'MUN'}
              onChange={(e) => update({ regiao: e.target.value })}
            >
              {regionOptions.map((o) => (
                <option key={o.value || 'default'} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
        )}
        <label>
          Vacina
          <select value={eff.vaccine ?? ''} onChange={(e) => update({ vacina: e.target.value, dose: '' })}>
            {!indicatorOnly && <option value="">Todas</option>}
            {vaccineOptions.map(([code, name]) => (
              <option key={code} value={code}>
                {name}
              </option>
            ))}
          </select>
        </label>
        <label>
          Dose
          <select
            value={eff.dose ?? ''}
            disabled={!eff.vaccine}
            onChange={(e) => update({ dose: e.target.value })}
          >
            {!indicatorOnly && <option value="">Todas</option>}
            {doseOptions.map((d) => (
              <option key={d} value={d}>
                {d}
              </option>
            ))}
          </select>
        </label>
        {!indicatorOnly && (
          <label>
            Faixa etária
            <select value={filters.faixa ?? ''} onChange={(e) => update({ faixa: e.target.value })}>
              <option value="">Todas</option>
              {BANDS.map((b) => (
                <option key={b} value={b}>
                  {ageBandLabel(b)}
                </option>
              ))}
            </select>
          </label>
        )}
        <button type="button" className="btn" onClick={() => update({ de: '', ate: '', ano: '', regiao: '', vacina: '', dose: '', faixa: '' })}>
          Limpar
        </button>
      </form>
    </div>
  )
}
