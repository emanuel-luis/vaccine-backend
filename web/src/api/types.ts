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
export type LowCoverage = S['LowCoverage']
export type Campaign = S['Campaign']
export type CampaignDetail = S['CampaignDetail']
export type CampaignRequest = S['CampaignRequest']
export type DailyPoint = S['DailyPoint']
export type Sources = S['Sources']
export type Source = S['Source']
export type Job = S['Job']
export type JobLogLine = S['JobLogLine']
export type DbtInvocation = S['DbtInvocation']
export type DbtNodeResult = S['DbtNodeResult']
export type Reconciliation = S['Reconciliation']
export type ReconCheck = S['Check']
export type ReconDiff = S['Diff']
export type Ingest = S['Ingest']
export type HourDoses = S['HourDoses']
export type RejectReason = S['RejectReason']
export type JobKind = 'load-reference' | 'dbt-build' | 'reconcile'
export type AlertsQuery = Omit<operations['lowCoverage']['parameters']['query'], 'level'> & {
  level: 'state' | 'municipality'
}

export type PeriodQuery = operations['summary']['parameters']['query']
/** The spec types level and metric as plain strings; the app only sends these values. */
export type MapQuery = Omit<operations['map']['parameters']['query'], 'level' | 'metric'> & {
  level: 'state' | 'municipality'
  metric: 'coverage' | 'doses'
}
