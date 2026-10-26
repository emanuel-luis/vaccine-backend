import type { components, operations } from './schema'

type S = components['schemas']

// Aliases over the generated schema, which states exactly which fields are nullable.
export type Summary = S['SummaryData']
export type TimeseriesPoint = S['TimeseriesPoint']
export type Region = S['Region']
export type Vaccine = S['Vaccine']
export type DosesByVaccine = S['VaccineDoses']
export type DosesByAgeBand = S['AgeBandDoses']
export type MapRow = S['MapArea']
export type HealthUnitRow = S['HealthUnitDoses']

export type PeriodQuery = operations['summary']['parameters']['query']
/** The spec types level and metric as plain strings; the app only sends these values. */
export type MapQuery = Omit<operations['map']['parameters']['query'], 'level' | 'metric'> & {
  level: 'state' | 'municipality'
  metric: 'coverage' | 'doses'
}
