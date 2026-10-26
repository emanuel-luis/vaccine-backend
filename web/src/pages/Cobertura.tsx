import { useState } from 'react'
import { Navigate, useNavigate, useParams } from 'react-router'
import { ApiError } from '../api/client'
import type { MapQuery, MapRow } from '../api/types'
import { useHealthUnits, useMapData, useVaccines } from '../api/queries'
import { useSession } from '../auth/session'
import { ChoroplethMap } from '../components/ChoroplethMap'
import { type Crumb, DrillBreadcrumb } from '../components/DrillBreadcrumb'
import { ExportButton } from '../components/ExportButton'
import { FilterBar } from '../components/FilterBar'
import { StatePanel } from '../components/StatePanel'
import { UnitTable } from '../components/UnitTable'
import { ageBandOf, effectiveVaccineDose, resolvePeriod, useFilters } from '../lib/filters'
import { regionParam, UF_BY_CODE, coverageHome } from '../lib/regions'
import type { Metric } from '../map/scale'

function errorMessage(error: unknown): string {
  if (error instanceof ApiError && error.status === 403) return 'Você não tem acesso a esta região.'
  if (error instanceof ApiError && error.status === 503)
    return 'Os dados ainda não estão disponíveis. Tente novamente em instantes.'
  return 'Não foi possível carregar os dados.'
}

export function Cobertura() {
  const { uf, municipio } = useParams()
  const session = useSession()
  const navigate = useNavigate()
  const [filters, , params] = useFilters()
  const [metric, setMetric] = useState<Metric>('coverage')
  const vaccinesQuery = useVaccines()
  const search = params.toString() ? `?${params}` : ''

  const home = coverageHome(session.scope)
  const needsRedirect =
    (session.scope?.level === 'UF' && !uf) || (session.scope?.level === 'MUN' && !municipio)
  const coverage = metric === 'coverage'
  const { vaccine, dose } = effectiveVaccineDose(filters, vaccinesQuery.data ?? [], coverage)
  const period = resolvePeriod(filters)
  // the map API accepts ageBand for doses only; the URL keeps faixa
  const ageBand = coverage ? undefined : ageBandOf(filters)
  const level: MapQuery['level'] = uf ? 'municipality' : 'state'

  const query: MapQuery = {
    level,
    state: uf,
    metric,
    vaccine,
    dose,
    ageBand,
    ...(coverage ? { year: Number(period.from.slice(0, 4)) } : { from: period.from, to: period.to }),
  }
  const ready = !needsRedirect && (!coverage || Boolean(vaccine && dose))
  const map = useMapData(query, ready)
  const rows = map.data?.data ?? []
  const suppressed = map.data?.meta.suppressedCells ?? 0

  const units = useHealthUnits(
    { municipality: municipio ?? '', ...period, region: undefined, vaccine, dose, ageBand },
    ready && Boolean(municipio),
  )

  if (needsRedirect && home !== '/cobertura') return <Navigate to={{ pathname: home, search }} replace />

  const goto = (row: MapRow) => {
    const target = level === 'state' ? `/cobertura/${row.abbr ?? UF_BY_CODE[row.code] ?? ''}` : `/cobertura/${uf}/${row.code}`
    void navigate({ pathname: target, search })
  }

  const munName = municipio ? (rows.find((r) => r.code === municipio)?.name ?? municipio) : undefined
  const scopeLevel = session.scope?.level
  const crumbs: Crumb[] = [{ label: 'Brasil', to: scopeLevel === 'BR' ? `/cobertura${search}` : undefined }]
  if (uf) crumbs.push({ label: uf, to: scopeLevel === 'BR' || scopeLevel === 'UF' ? `/cobertura/${uf}${search}` : undefined })
  if (munName) crumbs.push({ label: munName })

  const exportQuery = {
    region: regionParam(uf, municipio),
    state: uf,
    year: coverage ? String(query.year) : undefined,
    from: period.from,
    to: period.to,
    vaccine,
    dose,
    ageBand: ageBand === undefined ? undefined : String(ageBand),
  }

  return (
    <>
      <h1>Mapa de cobertura</h1>
      <FilterBar indicatorOnly={coverage} hideRegion />
      <div className="map-toolbar">
        <DrillBreadcrumb crumbs={crumbs} />
        <div role="group" aria-label="Métrica" className="segmented">
          <button type="button" className="btn" aria-pressed={coverage} onClick={() => setMetric('coverage')}>
            Cobertura
          </button>
          <button type="button" className="btn" aria-pressed={!coverage} onClick={() => setMetric('doses')}>
            Doses
          </button>
        </div>
        {session.roles.includes('gestor') && (
          <>
            {(!coverage || uf) && (
              <ExportButton dataset={coverage ? 'coverage-municipality' : 'doses-daily'} query={exportQuery} />
            )}
            {municipio && <ExportButton dataset="health-units" query={{ ...exportQuery, municipality: municipio }} />}
          </>
        )}
      </div>
      {coverage && !ready && !needsRedirect && !vaccinesQuery.isError && <StatePanel state="loading" />}
      {vaccinesQuery.isError && (
        <StatePanel state="error" message={errorMessage(vaccinesQuery.error)} onRetry={() => void vaccinesQuery.refetch()} />
      )}
      {map.isPending && ready && <StatePanel state="loading" />}
      {map.isError && <StatePanel state="error" message={errorMessage(map.error)} onRetry={() => void map.refetch()} />}
      {map.data && rows.length === 0 && <StatePanel state="empty" />}
      {map.data && rows.length > 0 && (
        <>
          {suppressed > 0 && (
            <p className="muted">
              {suppressed} {suppressed === 1 ? 'área tem' : 'áreas têm'} dados insuficientes (&lt; 5) e não mostram valor.
            </p>
          )}
          <ChoroplethMap
            geoUrl={uf ? `/geo/ibge_malha_municipios_${uf.toLowerCase()}.geojson` : '/geo/ibge_malha_uf.geojson'}
            rows={rows}
            metric={metric}
            label={`Mapa de ${coverage ? 'cobertura vacinal' : 'doses'}${uf ? ` de ${uf}` : ' do Brasil'}`}
            selectedCode={municipio}
            onSelect={goto}
          />
        </>
      )}
      {municipio && (
        <section aria-labelledby="unidades">
          <h2 id="unidades">Unidades de saúde{munName ? `: ${munName}` : ''}</h2>
          {units.isPending && <StatePanel state="loading" />}
          {units.isError && <StatePanel state="error" message={errorMessage(units.error)} onRetry={() => void units.refetch()} />}
          {units.data && units.data.length === 0 && <StatePanel state="empty" />}
          {units.data && units.data.length > 0 && <UnitTable units={units.data} />}
        </section>
      )}
    </>
  )
}
